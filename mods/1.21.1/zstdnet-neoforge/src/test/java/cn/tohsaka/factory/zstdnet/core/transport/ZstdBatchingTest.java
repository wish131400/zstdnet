package cn.tohsaka.factory.zstdnet.core.transport;

import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.ZstdCompressCtx;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ZstdBatchingTest {
    @Test
    void batchesSmallPacketsWithMatchingCountersAndLessWireTraffic() {
        AtomicLong rawSent = new AtomicLong();
        AtomicLong wireSent = new AtomicLong();
        AtomicLong rawReceived = new AtomicLong();
        AtomicLong wireReceived = new AtomicLong();
        EmbeddedChannel encoder = encoder(rawSent, wireSent);
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(
            rawReceived::addAndGet, wireReceived::addAndGet));
        List<ChannelFuture> writes = new ArrayList<>();
        ByteBuf expected = Unpooled.buffer();
        int oldWireBytes = 0;
        try (ZstdCompressCtx oldCompressor = new ZstdCompressCtx().setLevel(3)) {
            ByteBuffer source = ByteBuffer.allocateDirect(20);
            ByteBuffer compressed = ByteBuffer.allocateDirect(1024);
            for (int index = 0; index < 100; index++) {
                byte[] packet = new byte[20];
                packet[0] = 19;
                packet[1] = 8;
                packet[2] = (byte) index;
                expected.writeBytes(packet);
                writes.add(encoder.writeAndFlush(Unpooled.wrappedBuffer(packet)));
                source.clear();
                source.put(packet).flip();
                boolean complete;
                oldWireBytes += 12;
                do {
                    compressed.clear();
                    complete = oldCompressor.compressDirectByteBufferStream(compressed, source, EndDirective.FLUSH);
                    oldWireBytes += compressed.position();
                } while (!complete);
            }
        }
        assertNull(encoder.readOutbound());
        assertTrue(writes.stream().noneMatch(ChannelFuture::isDone));
        advance(encoder, 2);
        ByteBuf wire = encoder.readOutbound();
        assertNotNull(wire);
        int newWireBytes = wire.readableBytes();
        assertEquals(2000, wire.getInt(4));
        assertNull(encoder.readOutbound());
        assertTrue(newWireBytes < oldWireBytes / 2, newWireBytes + " vs " + oldWireBytes);
        assertTrue(writes.stream().allMatch(ChannelFuture::isSuccess));
        decoder.writeInbound(wire);
        ByteBuf restored = decoder.readInbound();
        assertEquals(expected, restored);
        assertEquals(2000, rawSent.get());
        assertEquals(newWireBytes, wireSent.get());
        assertEquals(rawSent.get(), rawReceived.get());
        assertEquals(wireSent.get(), wireReceived.get());
        System.out.println("100 small packets: per-packet=" + oldWireBytes
            + " bytes, batched=" + newWireBytes + " bytes, raw=2000 bytes");
        expected.release();
        restored.release();
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    @Test
    void repeatedFlushRequestsDoNotExtendDeadlineAndIdleWritesWaitForFlush() {
        EmbeddedChannel encoder = encoder(new AtomicLong(), new AtomicLong());
        ChannelFuture first = encoder.writeOneOutbound(Unpooled.wrappedBuffer(new byte[] {2, 8, 9}));
        advance(encoder, 10);
        assertNull(encoder.readOutbound());
        assertFalse(first.isDone());
        encoder.flushOutbound();
        advance(encoder, 1);
        ChannelFuture second = encoder.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {2, 8, 10}));
        assertNull(encoder.readOutbound());
        advance(encoder, 1);
        ByteBuf wire = encoder.readOutbound();
        assertNotNull(wire);
        assertEquals(6, wire.getInt(4));
        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        wire.release();
        encoder.finishAndReleaseAll();
    }

    @Test
    void boundsBatchesAndSendsLargeFramesImmediatelyInOrder() {
        EmbeddedChannel encoder = encoder(new AtomicLong(), new AtomicLong());
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(bytes -> { }, bytes -> { }));
        byte[] small = new byte[40 * 1024];
        byte[] large = new byte[70 * 1024];
        Arrays.fill(small, (byte) 7);
        new Random(42).nextBytes(large);
        encoder.writeOneOutbound(Unpooled.wrappedBuffer(small));
        encoder.writeAndFlush(Unpooled.wrappedBuffer(large));
        ByteBuf first = encoder.readOutbound();
        ByteBuf second = encoder.readOutbound();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(small.length, first.getInt(4));
        assertEquals(large.length, second.getInt(4));
        decoder.writeInbound(first, second);
        ByteBuf restoredSmall = decoder.readInbound();
        ByteBuf restoredLarge = decoder.readInbound();
        byte[] actualSmall = new byte[restoredSmall.readableBytes()];
        byte[] actualLarge = new byte[restoredLarge.readableBytes()];
        restoredSmall.readBytes(actualSmall);
        restoredLarge.readBytes(actualLarge);
        assertArrayEquals(small, actualSmall);
        assertArrayEquals(large, actualLarge);
        restoredSmall.release();
        restoredLarge.release();
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    @Test
    void fullBatchAndBackpressureDoNotWaitForTimer() {
        EmbeddedChannel encoder = encoder(new AtomicLong(), new AtomicLong());
        ChannelFuture first = encoder.writeOneOutbound(Unpooled.wrappedBuffer(new byte[32 * 1024]));
        ChannelFuture second = encoder.writeAndFlush(Unpooled.wrappedBuffer(new byte[32 * 1024]));
        ByteBuf full = encoder.readOutbound();
        assertNotNull(full);
        assertEquals(64 * 1024, full.getInt(4));
        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        full.release();

        encoder.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
        ChannelFuture small = encoder.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {2, 8, 9}));
        ByteBuf pressured = encoder.readOutbound();
        assertNotNull(pressured);
        assertTrue(small.isSuccess());
        pressured.release();
        encoder.finishAndReleaseAll();
    }

    @Test
    void closeFlushesPendingDisconnectDataImmediately() {
        EmbeddedChannel encoder = encoder(new AtomicLong(), new AtomicLong());
        ChannelFuture sent = encoder.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {2, 8, 9}));
        assertFalse(sent.isDone());
        encoder.close();
        ByteBuf wire = encoder.readOutbound();
        assertNotNull(wire);
        assertTrue(sent.isSuccess());
        wire.release();
        advance(encoder, 10);
        assertNull(encoder.readOutbound());
        encoder.finishAndReleaseAll();
    }

    @Test
    void removalFailsPendingWritesAndCancelsFlushTask() {
        EmbeddedChannel encoder = encoder(new AtomicLong(), new AtomicLong());
        ByteBuf input = Unpooled.wrappedBuffer(new byte[] {2, 8, 9});
        ChannelFuture sent = encoder.writeAndFlush(input);
        assertEquals(0, input.refCnt());
        encoder.pipeline().remove(ZstdFramedCodec.Encoder.class);
        assertInstanceOf(ClosedChannelException.class, sent.cause());
        advance(encoder, 10);
        assertNull(encoder.readOutbound());
        encoder.finishAndReleaseAll();
    }

    @Test
    void downstreamFailureReachesEveryOriginalWrite() {
        IOException failure = new IOException("simulated transport failure");
        EmbeddedChannel encoder = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ((ByteBuf) message).release();
                promise.setFailure(failure);
            }
        }, new ZstdFramedCodec.Encoder(3, bytes -> { }, bytes -> { }));
        encoder.freezeTime();
        ChannelFuture first = encoder.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {2, 8, 9}));
        ChannelFuture second = encoder.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {2, 8, 10}));
        encoder.advanceTimeBy(2, TimeUnit.MILLISECONDS);
        encoder.runScheduledPendingTasks();
        assertThrows(Exception.class, encoder::checkException);
        assertSame(failure, first.cause());
        assertSame(failure, second.cause());
        assertFalse(encoder.isOpen());
        encoder.finishAndReleaseAll();
    }

    private static EmbeddedChannel encoder(AtomicLong raw, AtomicLong wire) {
        EmbeddedChannel channel = new EmbeddedChannel(new ZstdFramedCodec.Encoder(3,
            raw::addAndGet, wire::addAndGet));
        channel.freezeTime();
        return channel;
    }

    private static void advance(EmbeddedChannel channel, int millis) {
        channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();
        channel.checkException();
    }
}
