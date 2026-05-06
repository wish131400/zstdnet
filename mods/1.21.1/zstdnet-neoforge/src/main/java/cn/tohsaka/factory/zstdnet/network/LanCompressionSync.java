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
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 1048576;

    private static final Logger LOGGER = LoggerFactory.getLogger(LanCompressionSync.class);
    private static final String PROTOCOL_VERSION = "1";
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    private LanCompressionSync() {
    }

    public static void init(IEventBus modEventBus) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        modEventBus.addListener(LanCompressionSync::onRegisterPayloadHandlers);
    }

    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToClient(PrepareMessage.TYPE, PrepareMessage.STREAM_CODEC, PrepareMessage::handle);
        registrar.playToServer(ReadyMessage.TYPE, ReadyMessage.STREAM_CODEC, ReadyMessage::handle);
        registrar.playToClient(ActivateMessage.TYPE, ActivateMessage.STREAM_CODEC, ActivateMessage::handle);
    }

    public static void requestCompressionUpgrade(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new PrepareMessage(LAN_THRESHOLD));
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

        private static void handle(PrepareMessage message, IPayloadContext context) {
            context.enqueueWork(() -> PacketDistributor.sendToServer(new ReadyMessage(message.threshold)));
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

        private static void handle(ReadyMessage message, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) {
                    LOGGER.warn("[zstdnet-server] ignored LAN compression ready packet without a server player context.");
                    return;
                }

                player.connection.getConnection().setupCompression(message.threshold, true);
                PacketDistributor.sendToPlayer(player, new ActivateMessage(message.threshold));
                LOGGER.info(
                    "[zstdnet-server] LAN compression threshold {} activated for {}.",
                    message.threshold,
                    player.getGameProfile().getName()
                );
            });
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

        private static void handle(ActivateMessage message, IPayloadContext context) {
            context.enqueueWork(() -> applyClientThreshold(message.threshold));
        }
    }
}
