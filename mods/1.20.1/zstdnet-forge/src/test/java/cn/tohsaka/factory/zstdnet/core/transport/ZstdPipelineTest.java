package cn.tohsaka.factory.zstdnet.core.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CipherDecoder;
import net.minecraft.network.CipherEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZstdPipelineTest {
    @Test
    void initialSyncDoesNotQueueVanillaDataAheadOfActivation() throws Exception {
        for (boolean encrypted : new boolean[] {false, true}) {
            try (ActivationChannel fixture = new ActivationChannel(encrypted)) {
                FriendlyByteBuf prepare = new FriendlyByteBuf(Unpooled.buffer().writeByte(7));
                FriendlyByteBuf initial = new FriendlyByteBuf(Unpooled.buffer().writeZero(128 * 1024));
                try {
                    fixture.offer(prepare);
                    ChannelPromise initialPromise = fixture.channel.newPromise();
                    fixture.channel.writeAndFlush(new ClientboundCustomPayloadPacket(
                        new ResourceLocation("test", "initial_sync"), initial), initialPromise);
                    assertEquals(1, fixture.queued.size(),
                        "Only Prepare may enter the socket queue before the peer is ready");
                    assertFalse(initialPromise.isDone());

                    fixture.activate();
                    assertEquals(3, fixture.queued.size());
                    assertNotNull(fixture.channel.pipeline().get("zstdnet_encode"));
                    assertNull(fixture.channel.pipeline().get("zstdnet_upgrade_gate"));
                    assertFalse(initialPromise.isDone(), "Releasing the gate is not socket-write completion");

                    EmbeddedChannel receiver = new EmbeddedChannel();
                    if (encrypted) {
                        receiver.pipeline().addLast("decrypt", new CipherDecoder(cipher(Cipher.DECRYPT_MODE)));
                    }
                    receiver.pipeline().addLast("transport", new ZstdFramedCodec.Decoder(bytes -> { }, bytes -> { },
                        () -> receiver.pipeline().remove("decompress")));
                    receiver.pipeline().addLast("splitter", new Varint21FrameDecoder());
                    receiver.pipeline().addLast("decompress", new CompressionDecoder(256, true));
                    try {
                        ByteBuf incoming = Unpooled.buffer();
                        for (ByteBuf frame : fixture.queued) {
                            incoming.writeBytes(frame, frame.readerIndex(), frame.readableBytes());
                        }
                        receiver.writeInbound(incoming);
                        assertPayload(receiver, new byte[] {7});
                        assertPayload(receiver, new byte[] {9});
                        assertPayload(receiver, new byte[128 * 1024]);
                        assertNull(receiver.readInbound());
                        fixture.promises.forEach(ChannelPromise::trySuccess);
                        assertTrue(initialPromise.isSuccess());
                    } finally {
                        receiver.finishAndReleaseAll();
                    }
                } finally {
                    prepare.release();
                    initial.release();
                }
            }
        }
    }

    @Test
    void initialSyncTimeoutFallsBackAndLateActivationStillWorks() throws Exception {
        try (ActivationChannel fixture = new ActivationChannel(false)) {
            FriendlyByteBuf prepare = new FriendlyByteBuf(Unpooled.buffer().writeByte(7));
            FriendlyByteBuf initial = new FriendlyByteBuf(Unpooled.buffer().writeByte(8));
            try {
                fixture.offer(prepare);
                fixture.channel.writeAndFlush(new ClientboundCustomPayloadPacket(
                    new ResourceLocation("test", "initial_sync"), initial));
                fixture.channel.advanceTimeBy(4999, TimeUnit.MILLISECONDS);
                fixture.channel.runScheduledPendingTasks();
                assertEquals(1, fixture.queued.size());
                fixture.channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
                fixture.channel.runScheduledPendingTasks();
                assertEquals(2, fixture.queued.size());
                assertNull(fixture.channel.pipeline().get("zstdnet_upgrade_gate"));
                assertNull(fixture.channel.pipeline().get("zstdnet_encode"));
                assertTrue(fixture.channel.isOpen());
                assertTrue(ZstdPipeline.consumeUpgradeOffer(fixture.connection));
                fixture.activate();
                assertNotNull(fixture.channel.pipeline().get("zstdnet_encode"));
            } finally {
                prepare.release();
                initial.release();
            }
        }
    }

    @Test
    void closingDuringInitialSyncFailsDeferredPromises() throws Exception {
        try (ActivationChannel fixture = new ActivationChannel(false)) {
            FriendlyByteBuf prepare = new FriendlyByteBuf(Unpooled.buffer().writeByte(7));
            FriendlyByteBuf initial = new FriendlyByteBuf(Unpooled.buffer().writeByte(8));
            try {
                fixture.offer(prepare);
                ChannelPromise promise = fixture.channel.newPromise();
                fixture.channel.writeAndFlush(new ClientboundCustomPayloadPacket(
                    new ResourceLocation("test", "initial_sync"), initial), promise);
                fixture.channel.close();
                assertTrue(promise.isDone());
                assertFalse(promise.isSuccess());
                fixture.channel.advanceTimeBy(5, TimeUnit.SECONDS);
                fixture.channel.runScheduledPendingTasks();
                assertEquals(1, fixture.queued.size());
            } finally {
                prepare.release();
                initial.release();
            }
        }
    }

    @Test
    void repeatedUpgradeRequestsDoNotSendAnotherPrepare() throws Exception {
        try (ActivationChannel fixture = new ActivationChannel(false)) {
            FriendlyByteBuf prepare = new FriendlyByteBuf(Unpooled.buffer().writeByte(7));
            try {
                fixture.offer(prepare);
                ZstdPipeline.offerUpgrade(fixture.connection, () -> {
                    throw new AssertionError("The second offer must not construct another packet");
                });
                fixture.channel.runPendingTasks();
                assertEquals(1, fixture.queued.size());
                fixture.activate();
                ZstdPipeline.offerUpgrade(fixture.connection, () -> {
                    throw new AssertionError("A completed upgrade must not restart negotiation");
                });
                assertEquals(2, fixture.queued.size());
            } finally {
                prepare.release();
            }
        }
    }

    @Test
    void deferredInitialPacketsKeepOrderAndDoNotRepeatUpstreamFilters() throws Exception {
        try (ActivationChannel fixture = new ActivationChannel(false)) {
            FriendlyByteBuf prepare = new FriendlyByteBuf(Unpooled.buffer().writeByte(7));
            FriendlyByteBuf first = new FriendlyByteBuf(Unpooled.buffer().writeByte(10));
            FriendlyByteBuf second = new FriendlyByteBuf(Unpooled.buffer().writeByte(11));
            try {
                int[] filtered = {0};
                fixture.channel.pipeline().addLast("upstream_filter", new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                        filtered[0]++;
                        context.write(message, promise);
                    }
                });
                fixture.offer(prepare);
                fixture.channel.writeAndFlush(new ClientboundCustomPayloadPacket(new ResourceLocation("test", "first"), first));
                fixture.channel.writeAndFlush(new ClientboundCustomPayloadPacket(new ResourceLocation("test", "second"), second));
                fixture.activate();
                assertEquals(4, filtered[0]);
                fixture.channel.advanceTimeBy(2, TimeUnit.MILLISECONDS);
                fixture.channel.runScheduledPendingTasks();
                EmbeddedChannel receiver = new EmbeddedChannel(new ZstdFramedCodec.Decoder(bytes -> { }, bytes -> { }),
                    new Varint21FrameDecoder());
                try {
                    for (int index = 2; index < fixture.queued.size(); index++) {
                        receiver.writeInbound(fixture.queued.get(index).retainedDuplicate());
                    }
                    assertPayload(receiver, new byte[] {10});
                    assertPayload(receiver, new byte[] {11});
                    assertNull(receiver.readInbound());
                } finally {
                    receiver.finishAndReleaseAll();
                }
            } finally {
                prepare.release();
                first.release();
                second.release();
            }
        }
    }

    @Test
    void initialSyncPacketLimitReleasesTheQueueWithoutWaitingForTimeout() throws Exception {
        try (ActivationChannel fixture = new ActivationChannel(false)) {
            FriendlyByteBuf prepare = new FriendlyByteBuf(Unpooled.buffer().writeByte(7));
            FriendlyByteBuf initial = new FriendlyByteBuf(Unpooled.buffer().writeByte(8));
            try {
                fixture.offer(prepare);
                for (int index = 0; index < InitialSyncGate.MAX_PACKETS; index++) {
                    fixture.channel.writeAndFlush(new ClientboundCustomPayloadPacket(
                        new ResourceLocation("test", "initial_sync"), initial));
                }
                assertNull(fixture.channel.pipeline().get("zstdnet_upgrade_gate"));
                assertNull(fixture.channel.pipeline().get("zstdnet_encode"));
                assertEquals(InitialSyncGate.MAX_PACKETS + 1, fixture.queued.size());
                assertTrue(fixture.channel.isOpen());
            } finally {
                prepare.release();
                initial.release();
            }
        }
    }

    @Test
    void activationDoesNotWaitForQueuedSocketWritesToComplete() throws Exception {
        for (boolean encrypted : new boolean[] {false, true}) {
            try (ActivationChannel fixture = new ActivationChannel(encrypted)) {
                byte[] previous = new byte[512];
                Arrays.fill(previous, (byte) 8);
                fixture.channel.writeAndFlush(Unpooled.wrappedBuffer(previous));
                fixture.activate();
                assertEquals(2, fixture.queued.size());
                assertFalse(fixture.promises.get(1).isDone(), "The activation packet is still waiting in the socket queue");
                assertNotNull(fixture.channel.pipeline().get("zstdnet_encode"),
                    "Subsequent packets must switch after activation is encoded, even while its socket write is pending");
                assertNull(fixture.channel.pipeline().get("zstdnet_activation_boundary"));
                assertEquals(1, fixture.active);
                fixture.channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {10, 11}));
                fixture.channel.advanceTimeBy(2, TimeUnit.MILLISECONDS);
                fixture.channel.runScheduledPendingTasks();
                assertEquals(3, fixture.queued.size());
                assertTrue(fixture.promises.stream().noneMatch(ChannelPromise::isDone));

                EmbeddedChannel receiver = new EmbeddedChannel();
                if (encrypted) {
                    receiver.pipeline().addLast("decrypt", new CipherDecoder(cipher(Cipher.DECRYPT_MODE)));
                }
                receiver.pipeline().addLast("transport", new ZstdFramedCodec.Decoder(bytes -> { }, bytes -> { },
                    () -> receiver.pipeline().remove("decompress")));
                receiver.pipeline().addLast("splitter", new Varint21FrameDecoder());
                receiver.pipeline().addLast("decompress", new CompressionDecoder(256, true));
                try {
                    for (ByteBuf frame : fixture.queued) {
                        receiver.writeInbound(frame.retainedDuplicate());
                    }
                    assertPayload(receiver, previous);
                    assertPayload(receiver, new byte[] {9});
                    assertPayload(receiver, new byte[] {10, 11});
                    assertNull(receiver.readInbound());
                } finally {
                    receiver.finishAndReleaseAll();
                }
            }
        }
    }

    @Test
    void activationWaitsForAnUpstreamPacketFilterBeforeSwitching() throws Exception {
        try (ActivationChannel fixture = new ActivationChannel(false)) {
            Runnable[] deferred = {null};
            fixture.channel.pipeline().addLast("deferred_filter", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    deferred[0] = () -> context.writeAndFlush(message, promise);
                }
            });
            fixture.activate();
            assertNotNull(deferred[0]);
            assertTrue(fixture.queued.isEmpty());
            assertNull(fixture.channel.pipeline().get("zstdnet_encode"));
            deferred[0].run();
            assertEquals(1, fixture.queued.size());
            assertNotNull(fixture.channel.pipeline().get("zstdnet_encode"));
            assertEquals(1, fixture.active);
        }
    }

    @Test
    void failedActivationEncodingDoesNotEnableZstd() throws Exception {
        try (ActivationChannel fixture = new ActivationChannel(false)) {
            fixture.rejectEncoding = true;
            fixture.activate();
            assertNull(fixture.channel.pipeline().get("zstdnet_encode"));
            assertEquals(0, fixture.active);
            assertFalse(fixture.channel.isOpen());
            assertThrows(EncoderException.class, fixture.channel::checkException);
        }
    }

    @Test
    void delayedActivationWriteFailureClosesTheUpgradedConnection() throws Exception {
        try (ActivationChannel fixture = new ActivationChannel(false)) {
            fixture.activate();
            assertEquals(1, fixture.active);
            assertFalse(fixture.promises.get(0).isDone());
            fixture.promises.get(0).tryFailure(new IllegalStateException("socket write failed"));
            fixture.channel.runPendingTasks();
            assertFalse(fixture.channel.isOpen());
            assertEquals(1, fixture.inactive);
            assertThrows(IllegalStateException.class, fixture.channel::checkException);
        }
    }

    @Test
    void failedActivationPacketDoesNotRunSuccessCallback() {
        int[] activations = {0};
        PacketSendListener listener = ZstdPipeline.onSendSuccess(() -> activations[0]++);
        assertNull(listener.onFailure());
        assertEquals(0, activations[0]);
        listener.onSuccess();
        assertEquals(1, activations[0]);
    }

    @Test
    void compressionChangesCompleteBeforeTheNextEventLoopWrite() throws Exception {
        EmbeddedChannel channel = newChannel();
        Connection connection = connection(channel);
        ZstdPipeline.disableVanillaCompression(connection,
            () -> ZstdPipeline.installClientEncoder(connection, 3));
        assertNull(channel.pipeline().get("compress"));
        assertNotNull(channel.pipeline().get("zstdnet_encode"));
        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[] {8, 9}));
        assertZstdPacket(channel);
        channel.finishAndReleaseAll();
    }

    @Test
    void decoderInstallationTransfersBufferedVanillaPayload() throws Exception {
        assertBufferedVanillaFrameSurvivesUpgrade(new byte[] {3, 8, 9, 10}, 2, false);
    }

    @Test
    void encryptedPartialPacketSurvivesUpgradeAndKeepsCipherState() throws Exception {
        assertBufferedVanillaFrameSurvivesUpgrade(new byte[] {3, 8, 9, 10}, 2, true);
    }

    @Test
    void decoderInstallationTransfersBufferedVanillaLength() throws Exception {
        byte[] frame = new byte[202];
        frame[0] = (byte) 0xC8;
        frame[1] = 1;
        assertBufferedVanillaFrameSurvivesUpgrade(frame, 1, false);
    }

    @Test
    void closedConnectionsDoNotRunUpgradeCallbacks() throws Exception {
        EmbeddedChannel channel = newChannel();
        Connection connection = connection(channel);
        channel.close();
        assertDoesNotThrow(() -> ZstdPipeline.installClientDecoder(connection,
            () -> { throw new AssertionError("Ready must not be sent on a closed connection"); }));
        assertDoesNotThrow(() -> ZstdPipeline.disableVanillaCompression(connection,
            () -> { throw new AssertionError("A closed connection must not activate compression"); }));
        assertDoesNotThrow(() -> ZstdPipeline.installClientEncoder(connection, 3));
        channel.finishAndReleaseAll();
    }

    private static void assertBufferedVanillaFrameSurvivesUpgrade(byte[] frame, int split, boolean encrypted) throws Exception {
        EmbeddedChannel channel = newChannel();
        channel.pipeline().addLast("splitter", new Varint21FrameDecoder());
        Cipher encryption = cipher(Cipher.ENCRYPT_MODE);
        if (encrypted) {
            channel.pipeline().addBefore("splitter", "decrypt", new CipherDecoder(cipher(Cipher.DECRYPT_MODE)));
        }
        byte[] wire = encrypted ? encryption.update(frame) : frame;
        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(wire, 0, split)));
        boolean[] ready = {false};
        ZstdPipeline.installClientDecoder(connection(channel), () -> ready[0] = true);
        channel.runPendingTasks();
        assertTrue(ready[0]);
        assertNotNull(channel.pipeline().get("zstdnet_decode"));
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(wire, split, wire.length - split)));
        channel.runPendingTasks();
        ByteBuf packet = channel.readInbound();
        int prefix = frame.length > 128 ? 2 : 1;
        byte[] payload = new byte[packet.readableBytes()];
        packet.readBytes(payload);
        assertArrayEquals(Arrays.copyOfRange(frame, prefix, frame.length), payload);
        packet.release();
        assertTrue(ready[0]);
        assertNotNull(channel.pipeline().get("zstdnet_decode"));
        assertNull(channel.readInbound());

        EmbeddedChannel sender = newChannel();
        if (encrypted) {
            sender.pipeline().addBefore("prepender", "encrypt", new CipherEncoder(encryption));
        }
        ZstdPipeline.installClientEncoder(connection(sender), 3);
        sender.writeOutbound(Unpooled.wrappedBuffer(new byte[] {8, 9}));
        sender.advanceTimeBy(2, TimeUnit.MILLISECONDS);
        sender.runScheduledPendingTasks();
        assertTrue(channel.writeInbound((ByteBuf) sender.readOutbound()));
        ByteBuf compressedPacket = channel.readInbound();
        assertEquals(2, compressedPacket.readableBytes());
        assertEquals(8, compressedPacket.readUnsignedByte());
        assertEquals(9, compressedPacket.readUnsignedByte());
        compressedPacket.release();
        assertNull(channel.readInbound());
        sender.finishAndReleaseAll();
        channel.finishAndReleaseAll();
    }

    @Test
    void decoderPreparationDoesNotWaitForSocketReadToEndAtAPacketBoundary() throws Exception {
        EmbeddedChannel channel = newChannel();
        channel.pipeline().addLast("splitter", new Varint21FrameDecoder());
        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {3, 8})));
        boolean[] ready = {false};
        ZstdPipeline.installClientDecoder(connection(channel), () -> ready[0] = true);
        channel.runPendingTasks();
        assertTrue(ready[0], "Ready must not wait for a continuously fragmented stream to become empty");
        assertNotNull(channel.pipeline().get("zstdnet_decode"));

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {9, 10, 3, 11})));
        ByteBuf first = channel.readInbound();
        assertEquals(3, first.readableBytes());
        assertEquals(8, first.readUnsignedByte());
        assertEquals(9, first.readUnsignedByte());
        assertEquals(10, first.readUnsignedByte());
        first.release();
        assertNull(channel.readInbound());

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {12, 13})));
        ByteBuf second = channel.readInbound();
        assertEquals(3, second.readableBytes());
        assertEquals(11, second.readUnsignedByte());
        assertEquals(12, second.readUnsignedByte());
        assertEquals(13, second.readUnsignedByte());
        second.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void decoderInstallationRequestedDuringAReadRunsAfterTheSplitterReturns() throws Exception {
        EmbeddedChannel channel = newChannel();
        channel.pipeline().addLast("splitter", new Varint21FrameDecoder());
        Connection connection = connection(channel);
        boolean[] ready = {false};
        channel.pipeline().addLast("prepare", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) {
                ZstdPipeline.installClientDecoder(connection, () -> ready[0] = true);
                context.fireChannelRead(message);
            }
        });
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {2, 8, 9, 3, 10})));
        channel.runPendingTasks();
        assertTrue(ready[0]);
        ByteBuf first = channel.readInbound();
        assertEquals(2, first.readableBytes());
        first.release();
        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] {11, 12})));
        ByteBuf second = channel.readInbound();
        assertEquals(3, second.readableBytes());
        assertEquals(10, second.readUnsignedByte());
        assertEquals(11, second.readUnsignedByte());
        assertEquals(12, second.readUnsignedByte());
        second.release();
        assertNull(channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void activationCallbackCanImmediatelySendWithoutVanillaCompression() throws Exception {
        EmbeddedChannel channel = newChannel();
        Connection connection = connection(channel);
        ZstdPipeline.disableVanillaCompression(connection, () -> {
            ZstdPipeline.installClientEncoder(connection, 3);
            channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[] {8, 9}));
        });
        channel.runPendingTasks();
        assertZstdPacket(channel);
        channel.finishAndReleaseAll();
    }

    @Test
    void encoderInstallationAlsoRemovesVanillaCompression() throws Exception {
        EmbeddedChannel channel = newChannel();
        ZstdPipeline.installClientEncoder(connection(channel), 3);
        channel.runPendingTasks();
        channel.writeOutbound(Unpooled.wrappedBuffer(new byte[] {8, 9}));
        assertZstdPacket(channel);
        channel.finishAndReleaseAll();
    }

    @Test
    void batchedPacketsKeepTheirBoundariesThroughMinecraftEncryption() throws Exception {
        EmbeddedChannel sender = newChannel();
        sender.freezeTime();
        sender.pipeline().addBefore("prepender", "encrypt", new CipherEncoder(cipher(Cipher.ENCRYPT_MODE)));
        ZstdPipeline.installClientEncoder(connection(sender), 3);
        sender.runPendingTasks();
        for (int index = 0; index < 100; index++) {
            sender.writeAndFlush(Unpooled.buffer(2).writeByte(8).writeByte(index));
        }
        assertNull(sender.readOutbound());
        sender.advanceTimeBy(2, TimeUnit.MILLISECONDS);
        sender.runScheduledPendingTasks();
        ByteBuf wire = sender.readOutbound();
        assertNotNull(wire);
        assertNull(sender.readOutbound());
        assertTrue(wire.readableBytes() < 100 * 12);

        EmbeddedChannel receiver = new EmbeddedChannel(new CipherDecoder(cipher(Cipher.DECRYPT_MODE)),
            new ZstdFramedCodec.Decoder(bytes -> { }, bytes -> { }), new Varint21FrameDecoder());
        receiver.writeInbound(wire);
        for (int index = 0; index < 100; index++) {
            ByteBuf packet = receiver.readInbound();
            assertNotNull(packet);
            assertEquals(2, packet.readableBytes());
            assertEquals(8, packet.readUnsignedByte());
            assertEquals(index, packet.readUnsignedByte());
            packet.release();
        }
        assertNull(receiver.readInbound());
        sender.finishAndReleaseAll();
        receiver.finishAndReleaseAll();
    }

    private static Cipher cipher(int mode) throws Exception {
        byte[] key = new byte[16];
        Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
        return cipher;
    }

    private static void assertPayload(EmbeddedChannel channel, byte[] expected) {
        ByteBuf packet = channel.readInbound();
        assertNotNull(packet);
        try {
            byte[] actual = new byte[packet.readableBytes()];
            packet.readBytes(actual);
            assertArrayEquals(expected, actual);
        } finally {
            packet.release();
        }
    }

    private static final class ActivationChannel implements AutoCloseable {
        private final EmbeddedChannel channel = newChannel();
        private final List<ByteBuf> queued = new ArrayList<>();
        private final List<ChannelPromise> promises = new ArrayList<>();
        private final FriendlyByteBuf payload = new FriendlyByteBuf(Unpooled.buffer().writeByte(9));
        private final Connection connection;
        private boolean rejectEncoding;
        private int active;
        private int inactive;

        private ActivationChannel(boolean encrypted) throws Exception {
            channel.freezeTime();
            channel.pipeline().addFirst("blocked_socket", new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    queued.add((ByteBuf) message);
                    promises.add(promise);
                }
            });
            if (encrypted) {
                channel.pipeline().addBefore("prepender", "encrypt", new CipherEncoder(cipher(Cipher.ENCRYPT_MODE)));
            }
            channel.pipeline().addLast("splitter", new Varint21FrameDecoder());
            channel.pipeline().addLast("encoder", new MessageToByteEncoder<ClientboundCustomPayloadPacket>() {
                @Override
                protected void encode(ChannelHandlerContext context, ClientboundCustomPayloadPacket packet, ByteBuf output) {
                    if (rejectEncoding) {
                        throw new IllegalStateException("activation encoding failed");
                    }
                    output.writeBytes(packet.getData(), packet.getData().readerIndex(), packet.getData().readableBytes());
                }
            });
            connection = connection(channel);
        }

        private void activate() {
            ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(
                new ResourceLocation("zstdnet", "lan_compression"), payload);
            ZstdPipeline.installDecoder(connection, bytes -> { }, bytes -> { }, () ->
                ZstdPipeline.sendUpgradeActivation(connection, packet, 3, bytes -> { }, bytes -> { },
                    () -> active++, () -> inactive++));
            channel.runPendingTasks();
        }

        private void offer(FriendlyByteBuf prepare) {
            ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(
                new ResourceLocation("zstdnet", "integrated_transport"), prepare);
            ZstdPipeline.offerUpgrade(connection, () -> packet);
            channel.runPendingTasks();
        }

        @Override
        public void close() {
            channel.finishAndReleaseAll();
            for (ChannelPromise promise : promises) {
                promise.trySuccess();
            }
            for (ByteBuf frame : queued) {
                frame.release();
            }
            payload.release();
        }
    }

    private static EmbeddedChannel newChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("prepender", new Varint21LengthFieldPrepender());
        channel.pipeline().addLast("compress", new CompressionEncoder(256));
        return channel;
    }

    private static Connection connection(Channel channel) throws Exception {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        for (Field field : Connection.class.getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                field.set(connection, channel);
                return connection;
            }
        }
        throw new AssertionError("Connection has no channel field");
    }

    private static void assertZstdPacket(EmbeddedChannel channel) {
        channel.advanceTimeBy(2, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        channel.checkException();
        assertNull(channel.pipeline().get("compress"));
        ByteBuf wire = channel.readOutbound();
        assertNotNull(wire);
        assertEquals(0x80808080, wire.getInt(wire.readerIndex()));
        EmbeddedChannel decoder = new EmbeddedChannel(new ZstdFramedCodec.Decoder(bytes -> { }, bytes -> { }));
        decoder.writeInbound(wire);
        ByteBuf raw = decoder.readInbound();
        assertEquals(3, raw.readableBytes());
        assertEquals(2, raw.readUnsignedByte());
        assertEquals(8, raw.readUnsignedByte());
        assertEquals(9, raw.readUnsignedByte());
        raw.release();
        decoder.finishAndReleaseAll();
    }
}
