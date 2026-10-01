package cn.tohsaka.factory.zstdnet.core.transport;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;

/** Compresses batches of Minecraft wire frames with one zstd context per connection. */
public final class ZstdFramedCodec {
    public static final int VERSION = 1;
    private static final int MAGIC = 0x80808080;
    private static final int HEADER_SIZE = 12;
    private static final int MAX_RAW_FRAME = 8 * 1024 * 1024 + 5;
    private static final int MAX_COMPRESSED_FRAME = 9 * 1024 * 1024;
    private static final int CHUNK_SIZE = 64 * 1024;
    private static final int MAX_BATCH_SIZE = 64 * 1024;
    private static final int FLUSH_DELAY_MILLIS = 2;

    private ZstdFramedCodec() {
    }

    public static final class Encoder extends ChannelDuplexHandler {
        private final ZstdCompressCtx compressor;
        private final LongConsumer rawBytes;
        private final LongConsumer wireBytes;
        private ByteBuf pending;
        private List<ChannelPromise> pendingPromises = new ArrayList<>();
        private ByteBuf scratch;
        private ScheduledFuture<?> scheduledFlush;
        private boolean closed;

        public Encoder(int level, LongConsumer rawBytes, LongConsumer wireBytes) {
            this.compressor = new ZstdCompressCtx().setLevel(level);
            this.rawBytes = rawBytes;
            this.wireBytes = wireBytes;
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (!(message instanceof ByteBuf input)) {
                try {
                    emitPending(context);
                    context.write(message, promise);
                } catch (Exception error) {
                    ReferenceCountUtil.release(message);
                    promise.tryFailure(error);
                    failConnection(context, error);
                }
                return;
            }
            try {
                int length = input.readableBytes();
                if (closed || length <= 0 || length > MAX_RAW_FRAME) {
                    throw new EncoderException("invalid Minecraft frame size or closed encoder: " + length);
                }
                if (pending != null && pending.readableBytes() + length > MAX_BATCH_SIZE) {
                    emitPending(context);
                }
                if (closed) {
                    throw new ClosedChannelException();
                }
                if (length >= MAX_BATCH_SIZE) {
                    emit(context, input, promise.isVoid() ? Collections.emptyList() : Collections.singletonList(promise));
                } else {
                    if (pending == null) {
                        pending = context.alloc().directBuffer(Math.max(length, 1024), MAX_BATCH_SIZE);
                    }
                    pending.writeBytes(input, input.readerIndex(), length);
                    if (!promise.isVoid()) {
                        pendingPromises.add(promise);
                    }
                    if (pending.readableBytes() == MAX_BATCH_SIZE) {
                        emitPending(context);
                    }
                }
            } catch (Exception error) {
                promise.tryFailure(error);
                failConnection(context, error);
            } finally {
                input.release();
            }
        }

        @Override
        public void flush(ChannelHandlerContext context) {
            if (pending == null || !context.channel().isWritable()) {
                flushNow(context);
            } else if (scheduledFlush == null) {
                // Do not extend the deadline when more packets arrive.
                scheduledFlush = context.executor().schedule(() -> {
                    scheduledFlush = null;
                    flushNow(context);
                }, FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
            }
        }

        private void flushNow(ChannelHandlerContext context) {
            cancelFlush();
            try {
                emitPending(context);
                context.flush();
            } catch (Exception error) {
                failConnection(context, error);
            }
        }

        private void emitPending(ChannelHandlerContext context) {
            if (pending == null) {
                return;
            }
            ByteBuf input = pending;
            List<ChannelPromise> promises = pendingPromises;
            pending = null;
            pendingPromises = new ArrayList<>();
            try {
                emit(context, input, promises);
            } finally {
                input.release();
            }
        }

        private void emit(ChannelHandlerContext context, ByteBuf input, List<ChannelPromise> promises) {
            try {
                ByteBuf frame = compress(context, input);
                ChannelPromise batchPromise = context.newPromise();
                batchPromise.addListener(result -> {
                    for (ChannelPromise promise : promises) {
                        if (result.isSuccess()) {
                            promise.trySuccess();
                        } else {
                            promise.tryFailure(result.cause());
                        }
                    }
                    if (!result.isSuccess()) {
                        // A lost batch also loses stream history, so this connection cannot continue.
                        failConnection(context, result.cause());
                    }
                });
                context.write(frame, batchPromise);
            } catch (Exception error) {
                for (ChannelPromise promise : promises) {
                    promise.tryFailure(error);
                }
                throw error;
            }
        }

        private ByteBuf compress(ChannelHandlerContext context, ByteBuf input) {
            int rawLength = input.readableBytes();
            ByteBuf copied = null;
            ByteBuf frame = context.alloc().buffer(HEADER_SIZE + Math.min(rawLength, CHUNK_SIZE),
                HEADER_SIZE + MAX_COMPRESSED_FRAME);
            try {
                ByteBuffer source;
                if (input.isDirect() && input.nioBufferCount() == 1) {
                    source = input.nioBuffer(input.readerIndex(), rawLength);
                } else {
                    copied = context.alloc().directBuffer(rawLength, rawLength);
                    copied.writeBytes(input, input.readerIndex(), rawLength);
                    source = copied.nioBuffer();
                }
                if (scratch == null) {
                    scratch = context.alloc().directBuffer(CHUNK_SIZE, CHUNK_SIZE);
                }
                frame.writeInt(MAGIC).writeInt(rawLength).writeInt(0);
                boolean finished;
                do {
                    ByteBuffer chunk = scratch.nioBuffer(0, CHUNK_SIZE);
                    finished = compressor.compressDirectByteBufferStream(chunk, source, EndDirective.FLUSH);
                    chunk.flip();
                    if (frame.readableBytes() - HEADER_SIZE + chunk.remaining() > MAX_COMPRESSED_FRAME) {
                        throw new EncoderException("compressed Minecraft batch exceeds limit");
                    }
                    frame.writeBytes(chunk);
                } while (!finished);
                frame.setInt(8, frame.readableBytes() - HEADER_SIZE);
                rawBytes.accept(rawLength);
                wireBytes.accept(frame.readableBytes());
                return frame;
            } catch (Exception error) {
                frame.release();
                throw error;
            } finally {
                if (copied != null) {
                    copied.release();
                }
            }
        }

        @Override
        public void close(ChannelHandlerContext context, ChannelPromise promise) {
            flushNow(context);
            context.close(promise);
        }

        @Override
        public void channelInactive(ChannelHandlerContext context) {
            releaseResources(new ClosedChannelException());
            context.fireChannelInactive();
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext context) {
            releaseResources(new ClosedChannelException());
        }

        private void failConnection(ChannelHandlerContext context, Throwable error) {
            releaseResources(error);
            context.fireExceptionCaught(error);
            context.close();
        }

        private void cancelFlush() {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
        }

        private void releaseResources(Throwable error) {
            cancelFlush();
            if (closed) {
                return;
            }
            closed = true;
            if (pending != null) {
                pending.release();
                pending = null;
            }
            for (ChannelPromise promise : pendingPromises) {
                promise.tryFailure(error);
            }
            pendingPromises.clear();
            if (scratch != null) {
                scratch.release();
                scratch = null;
            }
            compressor.close();
        }
    }

    public static final class Decoder extends ByteToMessageDecoder {
        private final ZstdDecompressCtx decompressor = new ZstdDecompressCtx();
        private final LongConsumer rawBytes;
        private final LongConsumer wireBytes;
        private final Runnable onFirstZstdFrame;
        private boolean receivedZstdFrame;

        public Decoder(LongConsumer rawBytes, LongConsumer wireBytes) {
            this(rawBytes, wireBytes, () -> { });
        }

        public Decoder(LongConsumer rawBytes, LongConsumer wireBytes, Runnable onFirstZstdFrame) {
            this.rawBytes = rawBytes;
            this.wireBytes = wireBytes;
            this.onFirstZstdFrame = onFirstZstdFrame;
        }

        @Override
        protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
            if (!input.isReadable()) {
                return;
            }
            int start = input.readerIndex();
            int prefixLength = Math.min(input.readableBytes(), 4);
            for (int index = 0; index < prefixLength; index++) {
                if ((input.getByte(start + index) & 0xFF) != 0x80) {
                    forwardVanillaFrame(input, output);
                    return;
                }
            }
            if (prefixLength < 4 || input.readableBytes() < HEADER_SIZE) {
                return;
            }
            if (input.getInt(start) != MAGIC) {
                throw new DecoderException("invalid zstd frame marker");
            }
            int rawLength = input.getInt(start + 4);
            int compressedLength = input.getInt(start + 8);
            if (rawLength <= 0 || rawLength > MAX_RAW_FRAME || compressedLength <= 0 || compressedLength > MAX_COMPRESSED_FRAME) {
                throw new DecoderException("invalid zstd frame lengths");
            }
            if (input.readableBytes() < HEADER_SIZE + compressedLength) {
                return;
            }

            input.skipBytes(HEADER_SIZE);
            ByteBuffer source = ByteBuffer.allocateDirect(compressedLength);
            input.readBytes(source);
            source.flip();
            ByteBuffer target = ByteBuffer.allocateDirect(rawLength);
            while (source.hasRemaining()) {
                int previousInput = source.position();
                int previousOutput = target.position();
                decompressor.decompressDirectByteBufferStream(target, source);
                if (source.position() == previousInput && target.position() == previousOutput) {
                    throw new DecoderException("zstd decoder made no progress");
                }
            }
            if (target.position() != rawLength) {
                throw new DecoderException("zstd frame size mismatch");
            }
            target.flip();
            ByteBuf decoded = context.alloc().buffer(rawLength);
            decoded.writeBytes(target);
            if (!receivedZstdFrame) {
                receivedZstdFrame = true;
                onFirstZstdFrame.run();
            }
            rawBytes.accept(rawLength);
            wireBytes.accept(HEADER_SIZE + compressedLength);
            output.add(decoded);
        }

        private void forwardVanillaFrame(ByteBuf input, List<Object> output) {
            int start = input.readerIndex();
            long size = 0;
            int prefix = 0;
            // Packet Fixer can extend Minecraft's normal three-byte frame length to a full VarInt.
            for (; prefix < 5; prefix++) {
                if (input.readableBytes() <= prefix) {
                    return;
                }
                int next = input.getByte(start + prefix) & 0xFF;
                size |= (long) (next & 0x7F) << (prefix * 7);
                if ((next & 0x80) == 0) {
                    prefix++;
                    if (size <= 0 || size > MAX_RAW_FRAME || input.readableBytes() < prefix + size) {
                        if (size > MAX_RAW_FRAME || size <= 0) {
                            throw new DecoderException("invalid vanilla frame size: " + size);
                        }
                        return;
                    }
                    ByteBuf frame = input.readRetainedSlice(prefix + (int) size);
                    rawBytes.accept(frame.readableBytes());
                    wireBytes.accept(frame.readableBytes());
                    output.add(frame);
                    return;
                }
            }
            throw new DecoderException("Minecraft frame length exceeds five bytes");
        }

        @Override
        protected void handlerRemoved0(ChannelHandlerContext context) throws Exception {
            decompressor.close();
            super.handlerRemoved0(context);
        }
    }
}
