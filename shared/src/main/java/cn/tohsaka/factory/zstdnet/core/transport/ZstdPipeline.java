package cn.tohsaka.factory.zstdnet.core.transport;

import io.netty.channel.Channel;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.AttributeKey;
import net.minecraft.ChatFormatting;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

public final class ZstdPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZstdPipeline.class);
    private static final String DECODER = "zstdnet_decode";
    private static final String ENCODER = "zstdnet_encode";
    private static final String ACTIVATION_BOUNDARY = "zstdnet_activation_boundary";
    private static final String UPGRADE_GATE = "zstdnet_upgrade_gate";
    private static final AttributeKey<Boolean> UPGRADE_OFFERED = AttributeKey.valueOf("zstdnet_upgrade_offered");
    private static final AttributeKey<Boolean> UPGRADE_REQUESTED = AttributeKey.valueOf("zstdnet_upgrade_requested");
    private static final AttributeKey<Boolean> KRYPTON_NOTICE_SENT = AttributeKey.valueOf("zstdnet_krypton_notice_sent");
    private static final Field CHANNEL_FIELD = findChannelField();
    private static final Field BUFFERED_INPUT_FIELD = findBufferedInputField();
    private static final LongConsumer IGNORE = bytes -> { };

    private ZstdPipeline() {
    }

    public static boolean canUpgrade(Connection connection) {
        Channel channel = channel(connection);
        if (channel == null || !channel.isOpen()) {
            return false;
        }
        ChannelPipeline pipeline = channel.pipeline();
        boolean supported = !isKryptonCompressionHandler(pipeline.get("decompress"))
            && !isKryptonCompressionHandler(pipeline.get("compress"));
        if (!supported) {
            LOGGER.warn("[zstdnet] KryptonFNP shares one native compressor between both directions; keeping vanilla compression for this connection.");
        }
        return supported;
    }

    public static void notifyKryptonFallback(Connection connection, Consumer<Component> recipient) {
        Channel channel = channel(connection);
        if (channel != null && channel.isOpen()
            && !Boolean.TRUE.equals(channel.attr(KRYPTON_NOTICE_SENT).getAndSet(true))) {
            recipient.accept(Component.translatable("zstdnet.krypton_fallback").withStyle(ChatFormatting.YELLOW));
        }
    }

    private static boolean isKryptonCompressionHandler(Object handler) {
        return handler != null && handler.getClass().getName()
            .startsWith("one.pkg.kfnp.shared.network.compression.MinecraftCompress");
    }

    public static void offerUpgrade(Connection connection) {
        Channel channel = channel(connection);
        if (channel != null && channel.isOpen()) {
            channel.attr(UPGRADE_OFFERED).set(true);
            LOGGER.info("[zstdnet] transport upgrade offered on {}.", channel.id());
        }
    }

    public static void offerUpgrade(Connection connection, Supplier<Packet<?>> prepare) {
        Channel channel = channel(connection);
        executeIfOpen(channel, () -> {
            if (Boolean.TRUE.equals(channel.attr(UPGRADE_REQUESTED).getAndSet(true))) {
                return;
            }
            Packet<?> packet = prepare.get();
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("encoder") != null) {
                pipeline.addAfter("encoder", UPGRADE_GATE, new InitialSyncGate(packet));
            }
            offerUpgrade(connection);
            connection.send(packet);
        });
    }

    public static boolean consumeUpgradeOffer(Connection connection) {
        Channel channel = channel(connection);
        boolean offered = channel != null && channel.isOpen()
            && Boolean.TRUE.equals(channel.attr(UPGRADE_OFFERED).getAndSet(false));
        if (offered) {
            LOGGER.info("[zstdnet] peer decoder ready on {}.", channel.id());
        }
        return offered;
    }

    public static PacketSendListener onSendSuccess(Runnable action) {
        return new PacketSendListener() {
            @Override
            public void onSuccess() {
                action.run();
            }
        };
    }

    public static void sendUpgradeReady(Connection connection, Packet<?> packet) {
        Channel channel = channel(connection);
        if (channel == null || !channel.isOpen()) {
            return;
        }
        connection.send(packet, onSendSuccess(() ->
            LOGGER.info("[zstdnet] decoder ready sent on {}.", channel.id())));
    }

    public static void sendUpgradeActivation(Connection connection, Packet<?> packet, int level,
                                              LongConsumer rawBytes, LongConsumer wireBytes,
                                              Runnable onActive, Runnable onInactive) {
        Channel channel = channel(connection);
        executeIfOpen(channel, () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("encoder") == null) {
                LOGGER.warn("[zstdnet] cannot locate the packet encoder; keeping vanilla transport on {}.", channel.id());
                return;
            }
            if (pipeline.get(UPGRADE_GATE) instanceof InitialSyncGate gate) {
                gate.allowActivation(packet);
            }
            pipeline.addAfter("encoder", ACTIVATION_BOUNDARY, new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    if (message != packet) {
                        context.write(message, promise);
                        return;
                    }
                    promise.addListener(result -> {
                        if (!result.isSuccess()) {
                            LOGGER.warn("[zstdnet] activation packet failed on {}; closing the connection.",
                                channel.id(), result.cause());
                            channel.close();
                        }
                    });
                    // Encode this last vanilla packet before switching subsequent writes. The socket
                    // promise can remain pending behind megabytes of already encoded login data.
                    context.write(message, promise);
                    pipeline.remove(this);
                    if (!channel.isOpen() || (promise.isDone() && !promise.isSuccess())) {
                        return;
                    }
                    LOGGER.info("[zstdnet] activation packet encoded on {}: socket_write_pending={}, writable={}.",
                        channel.id(), !promise.isDone(), channel.isWritable());
                    try {
                        installEncoder(connection, level, rawBytes, wireBytes, onActive, onInactive);
                        if (pipeline.get(UPGRADE_GATE) instanceof InitialSyncGate gate) {
                            gate.release("zstd_active");
                        }
                    } catch (RuntimeException error) {
                        context.fireExceptionCaught(error);
                        channel.close();
                    }
                }
            });
            connection.send(packet);
        });
    }

    public static void installDecoder(Connection connection, LongConsumer rawBytes, LongConsumer wireBytes, Runnable afterInstall) {
        Channel channel = channel(connection);
        if (channel == null || !channel.isOpen()) {
            return;
        }
        // Run after the current read, so the splitter cannot still be decoding its buffer.
        channel.eventLoop().execute(() -> {
            if (!channel.isOpen()) {
                return;
            }
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(DECODER) == null) {
                if (!(pipeline.get("splitter") instanceof ByteToMessageDecoder) || BUFFERED_INPUT_FIELD == null) {
                    LOGGER.warn("[zstdnet] cannot inspect the frame splitter; keeping vanilla transport on {}.", channel.id());
                    return;
                }
                ByteBuf buffered = bufferedInput(pipeline);
                ZstdFramedCodec.Decoder decoder = new ZstdFramedCodec.Decoder(rawBytes, wireBytes, () -> {
                    if (pipeline.get("decompress") != null) {
                        pipeline.remove("decompress");
                    }
                    LOGGER.info("[zstdnet] inbound Zstd active on {}: vanilla_decompress={}, encrypted={}.",
                        channel.id(), pipeline.get("decompress") != null, pipeline.get("decrypt") != null);
                });
                pipeline.addBefore("splitter", DECODER, decoder);
                int pendingBytes = buffered == null ? 0 : buffered.readableBytes();
                if (pendingBytes > 0) {
                    // The new decoder needs the old length prefix as well as its continuation.
                    // These bytes have already been decrypted, so feed them directly into the decoder.
                    ChannelHandlerContext context = pipeline.context(DECODER);
                    try {
                        decoder.channelRead(context, buffered.readRetainedSlice(pendingBytes));
                    } catch (Exception error) {
                        context.fireExceptionCaught(error);
                        channel.close();
                        return;
                    }
                }
                LOGGER.info("[zstdnet] inbound decoder prepared on {}: transferred {} pending vanilla bytes, encrypted={}.",
                    channel.id(), pendingBytes, pipeline.get("decrypt") != null);
            }
            if (afterInstall != null) {
                afterInstall.run();
            }
        });
    }

    public static void installEncoder(Connection connection, int level, LongConsumer rawBytes, LongConsumer wireBytes) {
        installEncoder(connection, level, rawBytes, wireBytes, () -> { }, () -> { });
    }

    public static void installEncoder(Connection connection, int level, LongConsumer rawBytes, LongConsumer wireBytes,
                                      Runnable onActive, Runnable onInactive) {
        Channel channel = channel(connection);
        executeIfOpen(channel, () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(ENCODER) == null) {
                ZstdFramedCodec.Encoder encoder = new ZstdFramedCodec.Encoder(level, rawBytes, wireBytes);
                if (pipeline.get("compress") != null) {
                    pipeline.remove("compress");
                }
                if (pipeline.get("encrypt") != null) {
                    pipeline.addAfter("encrypt", ENCODER, encoder);
                } else {
                    pipeline.addBefore("prepender", ENCODER, encoder);
                }
                LOGGER.info("[zstdnet] outbound Zstd active on {}: level={}, vanilla_compress={}, encrypted={}.",
                    channel.id(), level, pipeline.get("compress") != null, pipeline.get("encrypt") != null);
                onActive.run();
                channel.closeFuture().addListener(ignored -> onInactive.run());
            }
        });
    }

    public static void installClientDecoder(Connection connection, Runnable afterInstall) {
        Channel channel = channel(connection);
        if (channel != null && channel.isOpen()) {
            LOGGER.info("[zstdnet] transport prepare received on {}.", channel.id());
        }
        installDecoder(connection, IGNORE, IGNORE, afterInstall);
    }

    public static void installClientEncoder(Connection connection, int level) {
        installEncoder(connection, level, IGNORE, IGNORE);
    }

    public static void disableVanillaCompression(Connection connection, Runnable afterDisable) {
        Channel channel = channel(connection);
        executeIfOpen(channel, () -> {
            ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get("compress") != null) {
                pipeline.remove("compress");
            }
            if (afterDisable != null) {
                afterDisable.run();
            }
        });
    }

    private static void executeIfOpen(Channel channel, Runnable action) {
        if (channel == null || !channel.isOpen()) {
            return;
        }
        Runnable guarded = () -> {
            if (channel.isOpen()) {
                action.run();
            }
        };
        // Changing both encoders in this task keeps subsequent writes on the new format.
        if (channel.eventLoop().inEventLoop()) {
            guarded.run();
        } else {
            channel.eventLoop().execute(guarded);
        }
    }

    private static ByteBuf bufferedInput(ChannelPipeline pipeline) {
        try {
            return (ByteBuf) BUFFERED_INPUT_FIELD.get(pipeline.get("splitter"));
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot inspect the Minecraft frame splitter", error);
        }
    }

    private static Field findBufferedInputField() {
        try {
            Field field = ByteToMessageDecoder.class.getDeclaredField("cumulation");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warn("[zstdnet] frame splitter buffer access is unavailable; transport upgrades will be skipped.", error);
            return null;
        }
    }

    private static Channel channel(Connection connection) {
        try {
            return (Channel) CHANNEL_FIELD.get(connection);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot access Minecraft connection channel", error);
        }
    }

    private static Field findChannelField() {
        for (Field field : Connection.class.getDeclaredFields()) {
            if (Channel.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new IllegalStateException("Minecraft Connection has no Netty channel field");
    }
}
