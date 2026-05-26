/*
 * Copyright (c) 2026 wish
 *
 * This file is part of ZstdNet.
 *
 * ZstdNet is free software: you can redistribute it and/or modify
 * it under the terms of the MIT License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ZstdNet is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * MIT License for more details.
 *
 * You should have received a copy of the MIT License
 * along with ZstdNet. If not, see <https://opensource.org/licenses/MIT>.
 */

package cn.tohsaka.factory.zstdnet.network;

import cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher;
import cn.tohsaka.factory.zstdnet.Zstdnet;
import cn.tohsaka.factory.zstdnet.mixin.ServerGamePacketListenerImplAccessor;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 1048576;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean CLIENT_INITIALIZED = new AtomicBoolean(false);

    private LanCompressionSync() {
    }

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        PayloadTypeRegistry.playS2C().register(PrepareMessage.TYPE, PrepareMessage.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ReadyMessage.TYPE, ReadyMessage.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ActivateMessage.TYPE, ActivateMessage.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ServerHudMessage.TYPE, ServerHudMessage.STREAM_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(ReadyMessage.TYPE, (message, context) -> {
            context.server().execute(() -> {
                ServerPlayer player = context.player();
                ((ServerGamePacketListenerImplAccessor) player.connection).zstdnet$getConnection().setupCompression(message.threshold(), true);
                ServerPlayNetworking.send(player, new ActivateMessage(message.threshold()));
                LOGGER.info(
                    "[zstdnet-server] LAN compression threshold {} activated for {}.",
                    message.threshold(),
                    player.getGameProfile().getName()
                );
            });
        });
    }

    public static void initClient() {
        if (!CLIENT_INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        ClientPlayNetworking.registerGlobalReceiver(PrepareMessage.TYPE, (message, context) -> {
            context.client().execute(() -> ClientPlayNetworking.send(new ReadyMessage(message.threshold())));
        });

        ClientPlayNetworking.registerGlobalReceiver(ActivateMessage.TYPE, (message, context) -> {
            context.client().execute(() -> applyClientThreshold(message.threshold()));
        });

        ClientPlayNetworking.registerGlobalReceiver(ServerHudMessage.TYPE, (message, context) -> {
            context.client().execute(() -> ClientProxyPublisher.acceptRemoteServerHudSnapshot(message.toSnapshot()));
        });
    }

    public static void requestCompressionUpgrade(ServerPlayer player) {
        ServerPlayNetworking.send(player, new PrepareMessage(LAN_THRESHOLD));
        LOGGER.info(
            "[zstdnet-server] requested LAN compression threshold {} for {}.",
            LAN_THRESHOLD,
            player.getGameProfile().getName()
        );
    }

    public static void sendServerHudSnapshot(ServerPlayer player, ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        ServerPlayNetworking.send(player, ServerHudMessage.from(snapshot));
    }

    private static void applyClientThreshold(int threshold) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        minecraft.getConnection().getConnection().setupCompression(threshold, false);
        LOGGER.info("[zstdnet-client] compression threshold switched to {}.", threshold);
    }

    private record PrepareMessage(int threshold) implements CustomPacketPayload {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Zstdnet.MODID, "lan_compression_prepare");
        private static final Type<PrepareMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, PrepareMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            PrepareMessage::threshold,
            PrepareMessage::new
        );

        @Override
        public Type<PrepareMessage> type() {
            return TYPE;
        }
    }

    private record ReadyMessage(int threshold) implements CustomPacketPayload {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Zstdnet.MODID, "lan_compression_ready");
        private static final Type<ReadyMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, ReadyMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            ReadyMessage::threshold,
            ReadyMessage::new
        );

        @Override
        public Type<ReadyMessage> type() {
            return TYPE;
        }
    }

    private record ActivateMessage(int threshold) implements CustomPacketPayload {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Zstdnet.MODID, "lan_compression_activate");
        private static final Type<ActivateMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, ActivateMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT,
            ActivateMessage::threshold,
            ActivateMessage::new
        );

        @Override
        public Type<ActivateMessage> type() {
            return TYPE;
        }
    }

    private record ServerHudMessage(
        String mode,
        String listenHost,
        int listenPort,
        long rawBytes,
        long zstdBytes,
        long rawUpBytes,
        long rawDownBytes,
        long zstdUpBytes,
        long zstdDownBytes,
        long rawUpRate,
        long rawDownRate,
        long zstdUpRate,
        long zstdDownRate,
        long rawRate,
        long zstdRate,
        double ratioPercent,
        int connections
    ) implements CustomPacketPayload {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Zstdnet.MODID, "server_hud");
        private static final Type<ServerHudMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, ServerHudMessage> STREAM_CODEC = StreamCodec.of(
            ServerHudMessage::encode,
            ServerHudMessage::decode
        );

        private static ServerHudMessage from(ServerProxyBootstrap.ServerHudSnapshot snapshot) {
            return new ServerHudMessage(
                snapshot.mode(),
                snapshot.listenHost(),
                snapshot.listenPort(),
                snapshot.rawBytes(),
                snapshot.zstdBytes(),
                snapshot.rawUpBytes(),
                snapshot.rawDownBytes(),
                snapshot.zstdUpBytes(),
                snapshot.zstdDownBytes(),
                snapshot.rawUpRate(),
                snapshot.rawDownRate(),
                snapshot.zstdUpRate(),
                snapshot.zstdDownRate(),
                snapshot.rawRate(),
                snapshot.zstdRate(),
                snapshot.ratioPercent(),
                snapshot.connections()
            );
        }

        private static ServerHudMessage decode(RegistryFriendlyByteBuf buf) {
            return new ServerHudMessage(
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarInt(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readLong(),
                buf.readDouble(),
                buf.readVarInt()
            );
        }

        private static void encode(RegistryFriendlyByteBuf buf, ServerHudMessage message) {
            buf.writeUtf(message.mode);
            buf.writeUtf(message.listenHost);
            buf.writeVarInt(message.listenPort);
            buf.writeLong(message.rawBytes);
            buf.writeLong(message.zstdBytes);
            buf.writeLong(message.rawUpBytes);
            buf.writeLong(message.rawDownBytes);
            buf.writeLong(message.zstdUpBytes);
            buf.writeLong(message.zstdDownBytes);
            buf.writeLong(message.rawUpRate);
            buf.writeLong(message.rawDownRate);
            buf.writeLong(message.zstdUpRate);
            buf.writeLong(message.zstdDownRate);
            buf.writeLong(message.rawRate);
            buf.writeLong(message.zstdRate);
            buf.writeDouble(message.ratioPercent);
            buf.writeVarInt(message.connections);
        }

        @Override
        public Type<ServerHudMessage> type() {
            return TYPE;
        }

        private ServerProxyBootstrap.ServerHudSnapshot toSnapshot() {
            return new ServerProxyBootstrap.ServerHudSnapshot(
                mode,
                listenHost,
                listenPort,
                rawBytes,
                zstdBytes,
                rawUpBytes,
                rawDownBytes,
                zstdUpBytes,
                zstdDownBytes,
                rawUpRate,
                rawDownRate,
                zstdUpRate,
                zstdDownRate,
                rawRate,
                zstdRate,
                ratioPercent,
                connections
            );
        }
    }
}
