package cn.tohsaka.factory.zstdnet.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZstdFramedCodecTest {
    @Test
    void acceptsFragmentedVanillaFrameWithFourByteLength() {
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(ignored -> { }, ignored -> { }));
        int payloadLength = 2 * 1024 * 1024;
        ByteBuf frame = Unpooled.buffer(payloadLength + 4)
            .writeByte(0x80).writeByte(0x80).writeByte(0x80).writeByte(1).writeZero(payloadLength);
        decoder.writeInbound(frame.readRetainedSlice(3));
        assertNull(decoder.readInbound());
        decoder.writeInbound(frame.readRetainedSlice(1));
        assertNull(decoder.readInbound());
        assertTrue(decoder.writeInbound(frame));
        ByteBuf restored = decoder.readInbound();
        assertEquals(payloadLength + 4, restored.readableBytes());
        assertEquals(0x80808001, restored.readInt());
        restored.release();
        decoder.finishAndReleaseAll();
    }

    @Test
    void rejectsOversizedVanillaLengthBeforeWaitingForPayload() {
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(ignored -> { }, ignored -> { }));
        ByteBuf bad = Unpooled.buffer(5)
            .writeByte(0xFF).writeByte(0xFF).writeByte(0xFF).writeByte(0xFF).writeByte(7);
        assertThrows(DecoderException.class, () -> decoder.writeInbound(bad));
        decoder.pipeline().remove(ZstdFramedCodec.Decoder.class);
        decoder.finishAndReleaseAll();
    }

    @Test
    void compressesRepeatedFramesAcrossOneConnection() {
        EmbeddedChannel encoder = new EmbeddedChannel(new ZstdFramedCodec.Encoder(3, ignored -> { }, ignored -> { }));
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(ignored -> { }, ignored -> { }));
        byte[] raw = new byte[64 * 1024];
        Arrays.fill(raw, (byte) 'a');
        for (int count = 0; count < 3; count++) {
            assertTrue(encoder.writeOutbound(Unpooled.wrappedBuffer(raw)));
            ByteBuf compressed = encoder.readOutbound();
            assertTrue(compressed.readableBytes() < raw.length / 10);
            assertTrue(decoder.writeInbound(compressed));
            ByteBuf restored = decoder.readInbound();
            byte[] actual = new byte[restored.readableBytes()];
            restored.readBytes(actual);
            assertArrayEquals(raw, actual);
            restored.release();
        }
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    @Test
    void acceptsQueuedVanillaFrameAndFragmentedZstdFrame() {
        EmbeddedChannel encoder = new EmbeddedChannel(new ZstdFramedCodec.Encoder(3, ignored -> { }, ignored -> { }));
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(ignored -> { }, ignored -> { }));
        byte[] vanilla = {3, 1, 2, 3};
        assertTrue(decoder.writeInbound(Unpooled.wrappedBuffer(vanilla)));
        ByteBuf oldFrame = decoder.readInbound();
        assertEquals(4, oldFrame.readableBytes());
        oldFrame.release();

        byte[] raw = new byte[4096];
        Arrays.fill(raw, (byte) 42);
        encoder.writeOutbound(Unpooled.wrappedBuffer(raw));
        flushBatches(encoder);
        ByteBuf compressed = encoder.readOutbound();
        byte[] wire = new byte[compressed.readableBytes()];
        compressed.readBytes(wire);
        compressed.release();
        for (byte b : wire) {
            decoder.writeInbound(Unpooled.wrappedBuffer(new byte[] {b}));
        }
        ByteBuf restored = decoder.readInbound();
        byte[] actual = new byte[restored.readableBytes()];
        restored.readBytes(actual);
        assertArrayEquals(raw, actual);
        restored.release();
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    @Test
    void rejectsOversizedFrameBeforeAllocatingPayload() {
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(ignored -> { }, ignored -> { }));
        ByteBuf bad = Unpooled.buffer(12).writeInt(0x80808080).writeInt(Integer.MAX_VALUE).writeInt(1);
        assertThrows(DecoderException.class, () -> decoder.writeInbound(bad));
        decoder.pipeline().remove(ZstdFramedCodec.Decoder.class);
        decoder.finishAndReleaseAll();
    }

    @Test
    void acceptsCoalescedVanillaAndZstdFramesAndSwitchesInboundOnce() {
        EmbeddedChannel encoder = new EmbeddedChannel(new ZstdFramedCodec.Encoder(3, ignored -> { }, ignored -> { }));
        AtomicInteger switches = new AtomicInteger();
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(
            ignored -> { }, ignored -> { }, switches::incrementAndGet));
        byte[] raw = new byte[2048];
        Arrays.fill(raw, (byte) 7);
        ByteBuf combined = Unpooled.buffer();
        combined.writeBytes(new byte[] {2, 0, 1});
        for (int index = 0; index < 2; index++) {
            encoder.writeOutbound(Unpooled.wrappedBuffer(raw));
            flushBatches(encoder);
            ByteBuf compressed = encoder.readOutbound();
            combined.writeBytes(compressed);
            compressed.release();
        }

        assertTrue(decoder.writeInbound(combined));
        ByteBuf vanilla = decoder.readInbound();
        assertEquals(3, vanilla.readableBytes());
        vanilla.release();
        for (int index = 0; index < 2; index++) {
            ByteBuf restored = decoder.readInbound();
            byte[] actual = new byte[restored.readableBytes()];
            restored.readBytes(actual);
            assertArrayEquals(raw, actual);
            restored.release();
        }
        assertEquals(1, switches.get());
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    @Test
    void rejectsCorruptZstdPayload() {
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(ignored -> { }, ignored -> { }));
        ByteBuf bad = Unpooled.buffer(16).writeInt(0x80808080).writeInt(100).writeInt(4).writeInt(0);
        assertThrows(DecoderException.class, () -> decoder.writeInbound(bad));
        decoder.pipeline().remove(ZstdFramedCodec.Decoder.class);
        decoder.finishAndReleaseAll();
    }

    @Test
    void deliversQueuedVanillaFrameBeforeRemovingVanillaDecompressor() {
        EmbeddedChannel encoder = new EmbeddedChannel(new ZstdFramedCodec.Encoder(3, ignored -> { }, ignored -> { }));
        AtomicInteger vanillaFrames = new AtomicInteger();
        EmbeddedChannel[] channels = new EmbeddedChannel[1];
        EmbeddedChannel decoder = new EmbeddedChannel();
        channels[0] = decoder;
        decoder.pipeline().addLast("zstd", new ZstdFramedCodec.Decoder(ignored -> { }, ignored -> { },
            () -> channels[0].pipeline().remove("decompress")));
        decoder.pipeline().addLast("decompress", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext context, Object message) {
                    vanillaFrames.incrementAndGet();
                    context.fireChannelRead(message);
                }
            });
        encoder.writeOutbound(Unpooled.wrappedBuffer(new byte[] {2, 8, 9}));
        flushBatches(encoder);
        ByteBuf zstd = encoder.readOutbound();
        ByteBuf combined = Unpooled.buffer().writeBytes(new byte[] {2, 0, 1}).writeBytes(zstd);
        zstd.release();

        assertTrue(decoder.writeInbound(combined));
        assertEquals(1, vanillaFrames.get());
        assertNull(decoder.pipeline().get("decompress"));
        ((ByteBuf) decoder.readInbound()).release();
        ((ByteBuf) decoder.readInbound()).release();
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    private static void flushBatches(EmbeddedChannel channel) {
        channel.advanceTimeBy(2, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.checkException();
    }
}
