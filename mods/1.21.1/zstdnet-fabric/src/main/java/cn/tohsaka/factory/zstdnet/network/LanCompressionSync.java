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

import cn.tohsaka.factory.zstdnet.Zstdnet;
import cn.tohsaka.factory.zstdnet.mixin.ServerGamePacketListenerImplAccessor;
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
    }

    public static void requestCompressionUpgrade(ServerPlayer player) {
        ServerPlayNetworking.send(player, new PrepareMessage(LAN_THRESHOLD));
        LOGGER.info(
            "[zstdnet-server] requested LAN compression threshold {} for {}.",
            LAN_THRESHOLD,
            player.getGameProfile().getName()
        );
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
}
