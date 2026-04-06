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
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

import java.util.function.Supplier;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 1048576;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(Zstdnet.MODID, "lan_compression"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static boolean initialized;

    private LanCompressionSync() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        int id = 0;
        CHANNEL.messageBuilder(PrepareMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(PrepareMessage::encode)
            .decoder(PrepareMessage::decode)
            .consumerMainThread(PrepareMessage::handle)
            .add();
        CHANNEL.messageBuilder(ReadyMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(ReadyMessage::encode)
            .decoder(ReadyMessage::decode)
            .consumerMainThread(ReadyMessage::handle)
            .add();
        CHANNEL.messageBuilder(ActivateMessage.class, id, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ActivateMessage::encode)
            .decoder(ActivateMessage::decode)
            .consumerMainThread(ActivateMessage::handle)
            .add();
    }

    public static void requestCompressionUpgrade(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PrepareMessage(LAN_THRESHOLD));
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

    private record PrepareMessage(int threshold) {
        private static PrepareMessage decode(FriendlyByteBuf buf) {
            return new PrepareMessage(buf.readVarInt());
        }

        private static void encode(PrepareMessage message, FriendlyByteBuf buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(PrepareMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> CHANNEL.sendToServer(new ReadyMessage(message.threshold)));
            supplier.get().setPacketHandled(true);
        }
    }

    private record ReadyMessage(int threshold) {
        private static ReadyMessage decode(FriendlyByteBuf buf) {
            return new ReadyMessage(buf.readVarInt());
        }

        private static void encode(ReadyMessage message, FriendlyByteBuf buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(ReadyMessage message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                player.connection.connection.setupCompression(message.threshold, true);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ActivateMessage(message.threshold));
                LOGGER.info(
                    "[zstdnet-server] LAN compression threshold {} activated for {}.",
                    message.threshold,
                    player.getGameProfile().getName()
                );
            });
            context.setPacketHandled(true);
        }
    }

    private record ActivateMessage(int threshold) {
        private static ActivateMessage decode(FriendlyByteBuf buf) {
            return new ActivateMessage(buf.readVarInt());
        }

        private static void encode(ActivateMessage message, FriendlyByteBuf buf) {
            buf.writeVarInt(message.threshold);
        }

        private static void handle(ActivateMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> applyClientThreshold(message.threshold));
            supplier.get().setPacketHandled(true);
        }
    }
}
