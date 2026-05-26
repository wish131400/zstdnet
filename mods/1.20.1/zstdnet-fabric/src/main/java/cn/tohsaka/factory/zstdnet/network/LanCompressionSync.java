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
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 1048576;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation PREPARE_ID = new ResourceLocation(Zstdnet.MODID, "lan_compression_prepare");
    private static final ResourceLocation READY_ID = new ResourceLocation(Zstdnet.MODID, "lan_compression_ready");
    private static final ResourceLocation ACTIVATE_ID = new ResourceLocation(Zstdnet.MODID, "lan_compression_activate");
    private static final ResourceLocation SERVER_HUD_ID = new ResourceLocation(Zstdnet.MODID, "server_hud");

    private static boolean initialized;
    private static boolean clientInitialized;

    private LanCompressionSync() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        ServerPlayNetworking.registerGlobalReceiver(READY_ID, (server, player, handler, buf, responseSender) -> {
            int threshold = buf.readVarInt();
            server.execute(() -> {
                ((ServerGamePacketListenerImplAccessor) player.connection).zstdnet$getConnection().setupCompression(threshold, true);

                FriendlyByteBuf activate = PacketByteBufs.create();
                activate.writeVarInt(threshold);
                ServerPlayNetworking.send(player, ACTIVATE_ID, activate);
                LOGGER.info(
                    "[zstdnet-server] LAN compression threshold {} activated for {}.",
                    threshold,
                    player.getGameProfile().getName()
                );
            });
        });
    }

    public static void initClient() {
        if (clientInitialized || FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        clientInitialized = true;

        ClientPlayNetworking.registerGlobalReceiver(PREPARE_ID, (client, handler, buf, responseSender) -> {
            int threshold = buf.readVarInt();
            client.execute(() -> {
                FriendlyByteBuf ready = PacketByteBufs.create();
                ready.writeVarInt(threshold);
                ClientPlayNetworking.send(READY_ID, ready);
            });
        });

        ClientPlayNetworking.registerGlobalReceiver(ACTIVATE_ID, (client, handler, buf, responseSender) -> {
            int threshold = buf.readVarInt();
            client.execute(() -> applyClientThreshold(threshold));
        });

        ClientPlayNetworking.registerGlobalReceiver(SERVER_HUD_ID, (client, handler, buf, responseSender) -> {
            ServerProxyBootstrap.ServerHudSnapshot snapshot = decodeServerHudSnapshot(buf);
            client.execute(() -> ClientProxyPublisher.acceptRemoteServerHudSnapshot(snapshot));
        });
    }

    public static void requestCompressionUpgrade(ServerPlayer player) {
        FriendlyByteBuf prepare = PacketByteBufs.create();
        prepare.writeVarInt(LAN_THRESHOLD);
        ServerPlayNetworking.send(player, PREPARE_ID, prepare);
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
        FriendlyByteBuf buf = PacketByteBufs.create();
        encodeServerHudSnapshot(snapshot, buf);
        ServerPlayNetworking.send(player, SERVER_HUD_ID, buf);
    }

    private static void applyClientThreshold(int threshold) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }
        minecraft.getConnection().getConnection().setupCompression(threshold, false);
        LOGGER.info("[zstdnet-client] compression threshold switched to {}.", threshold);
    }

    private static void encodeServerHudSnapshot(ServerProxyBootstrap.ServerHudSnapshot snapshot, FriendlyByteBuf buf) {
        buf.writeUtf(snapshot.mode());
        buf.writeUtf(snapshot.listenHost());
        buf.writeVarInt(snapshot.listenPort());
        buf.writeLong(snapshot.rawBytes());
        buf.writeLong(snapshot.zstdBytes());
        buf.writeLong(snapshot.rawUpBytes());
        buf.writeLong(snapshot.rawDownBytes());
        buf.writeLong(snapshot.zstdUpBytes());
        buf.writeLong(snapshot.zstdDownBytes());
        buf.writeLong(snapshot.rawUpRate());
        buf.writeLong(snapshot.rawDownRate());
        buf.writeLong(snapshot.zstdUpRate());
        buf.writeLong(snapshot.zstdDownRate());
        buf.writeLong(snapshot.rawRate());
        buf.writeLong(snapshot.zstdRate());
        buf.writeDouble(snapshot.ratioPercent());
        buf.writeVarInt(snapshot.connections());
    }

    private static ServerProxyBootstrap.ServerHudSnapshot decodeServerHudSnapshot(FriendlyByteBuf buf) {
        return new ServerProxyBootstrap.ServerHudSnapshot(
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
}
