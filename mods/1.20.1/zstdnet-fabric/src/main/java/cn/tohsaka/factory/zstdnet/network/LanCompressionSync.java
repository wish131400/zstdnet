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
import cn.tohsaka.factory.zstdnet.ClientConfig;
import cn.tohsaka.factory.zstdnet.core.transport.ZstdPipeline;
import cn.tohsaka.factory.zstdnet.Zstdnet;
import cn.tohsaka.factory.zstdnet.mixin.ServerGamePacketListenerImplAccessor;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 256;
    private static final int MAX_REPORT_BYTES = 1024 * 1024;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation PREPARE_ID = new ResourceLocation(Zstdnet.MODID, "lan_compression_prepare");
    private static final ResourceLocation READY_ID = new ResourceLocation(Zstdnet.MODID, "lan_compression_ready");
    private static final ResourceLocation ACTIVATE_ID = new ResourceLocation(Zstdnet.MODID, "lan_compression_activate");
    private static final ResourceLocation INTEGRATED_PREPARE_ID = new ResourceLocation(Zstdnet.MODID, "integrated_prepare");
    private static final ResourceLocation INTEGRATED_READY_ID = new ResourceLocation(Zstdnet.MODID, "integrated_ready");
    private static final ResourceLocation SERVER_HUD_ID = new ResourceLocation(Zstdnet.MODID, "server_hud");
    private static final ResourceLocation TRAFFIC_REPORT_REQUEST_ID = new ResourceLocation(Zstdnet.MODID, "traffic_report_request");
    private static final ResourceLocation TRAFFIC_REPORT_RESPONSE_ID = new ResourceLocation(Zstdnet.MODID, "traffic_report_response");

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
            if (threshold == -1) {
                if (ServerPlayNetworking.canSend(player, INTEGRATED_PREPARE_ID)) {
                    activateIntegrated(player);
                }
                return;
            }
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

        ServerPlayNetworking.registerGlobalReceiver(INTEGRATED_READY_ID, (server, player, handler, buf, responseSender) -> {
            int version = buf.readVarInt();
            if (version == 1 && ServerPlayNetworking.canSend(player, INTEGRATED_PREPARE_ID)) {
                activateIntegrated(player);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(TRAFFIC_REPORT_REQUEST_ID, (server, player, handler, buf, responseSender) -> {
            String range = buf.readUtf(16);
            server.execute(() -> {
                FriendlyByteBuf response = PacketByteBufs.create();
                if (!player.hasPermissions(2)) {
                    response.writeBoolean(false);
                    response.writeUtf("需要服务器管理员权限才能导出流量报告。", MAX_REPORT_BYTES);
                } else {
                    try {
                        response.writeBoolean(true);
                        response.writeUtf(ServerProxyBootstrap.buildTrafficReport(range), MAX_REPORT_BYTES);
                    } catch (RuntimeException e) {
                        LOGGER.warn("[zstdnet-server] failed to build traffic report for {}: {}", player.getGameProfile().getName(), e.toString());
                        response.writeBoolean(false);
                        response.writeUtf("服务器生成流量报告失败：" + e.getMessage(), MAX_REPORT_BYTES);
                    }
                }
                ServerPlayNetworking.send(player, TRAFFIC_REPORT_RESPONSE_ID, response);
            });
        });
    }

    private static void activateIntegrated(ServerPlayer player) {
        Connection connection = ((ServerGamePacketListenerImplAccessor) player.connection).zstdnet$getConnection();
        if (!ZstdPipeline.consumeUpgradeOffer(connection)) {
            return;
        }
        ZstdPipeline.installDecoder(connection,
            ServerProxyBootstrap::addIntegratedRawUp,
            ServerProxyBootstrap::addIntegratedWireUp,
            () -> {
                FriendlyByteBuf activate = PacketByteBufs.create();
                activate.writeVarInt(-1);
                ZstdPipeline.sendUpgradeActivation(connection,
                    ServerPlayNetworking.createS2CPacket(ACTIVATE_ID, activate),
                    ServerProxyConfigFile.readLevel(),
                    ServerProxyBootstrap::addIntegratedRawDown,
                    ServerProxyBootstrap::addIntegratedWireDown,
                    () -> ServerProxyBootstrap.addIntegratedConnection(1),
                    () -> ServerProxyBootstrap.addIntegratedConnection(-1)
                );
            });
    }

    public static void initClient() {
        if (clientInitialized || FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            return;
        }
        clientInitialized = true;

        ClientPlayNetworking.registerGlobalReceiver(PREPARE_ID, (client, handler, buf, responseSender) -> {
            int threshold = buf.readVarInt();
            Connection connection = handler.getConnection();
            if (threshold == -1) {
                prepareClientTransport(client, connection, READY_ID, -1);
            } else {
                FriendlyByteBuf ready = PacketByteBufs.create();
                ready.writeVarInt(threshold);
                connection.send(ClientPlayNetworking.createC2SPacket(READY_ID, ready));
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(INTEGRATED_PREPARE_ID, (client, handler, buf, responseSender) -> {
            int version = buf.readVarInt();
            if (version != 1) {
                return;
            }
            prepareClientTransport(client, handler.getConnection(), INTEGRATED_READY_ID, 1);
        });

        ClientPlayNetworking.registerGlobalReceiver(ACTIVATE_ID, (client, handler, buf, responseSender) -> {
            int threshold = buf.readVarInt();
            Connection connection = handler.getConnection();
            if (threshold == -1) {
                ZstdPipeline.installClientEncoder(connection, ClientConfig.getLevel());
            } else {
                connection.setupCompression(threshold, false);
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(SERVER_HUD_ID, (client, handler, buf, responseSender) -> {
            ServerProxyBootstrap.ServerHudSnapshot snapshot = decodeServerHudSnapshot(buf);
            client.execute(() -> ClientProxyPublisher.acceptRemoteServerHudSnapshot(snapshot));
        });

        ClientPlayNetworking.registerGlobalReceiver(TRAFFIC_REPORT_RESPONSE_ID, (client, handler, buf, responseSender) -> {
            boolean success = buf.readBoolean();
            String payload = buf.readUtf(MAX_REPORT_BYTES);
            client.execute(() -> ClientProxyPublisher.acceptTrafficReportResponse(success, payload));
        });

    }

    private static void prepareClientTransport(Minecraft client, Connection connection, ResourceLocation readyId, int value) {
        if (ZstdPipeline.canUpgrade(connection)) {
            ZstdPipeline.installClientDecoder(connection, () -> {
                FriendlyByteBuf ready = PacketByteBufs.create();
                ready.writeVarInt(value);
                ZstdPipeline.sendUpgradeReady(connection, ClientPlayNetworking.createC2SPacket(readyId, ready));
            });
        } else {
            client.execute(() -> {
                if (client.getConnection() != null && client.getConnection().getConnection() == connection && client.player != null) {
                    ZstdPipeline.notifyKryptonFallback(connection, client.player::sendSystemMessage);
                }
            });
        }
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

    public static void requestIntegratedUpgrade(ServerPlayer player) {
        Connection connection = ((ServerGamePacketListenerImplAccessor) player.connection).zstdnet$getConnection();
        if (!ServerPlayNetworking.canSend(player, INTEGRATED_PREPARE_ID)) {
            return;
        }
        if (!ZstdPipeline.canUpgrade(connection)) {
            ZstdPipeline.notifyKryptonFallback(connection, player::sendSystemMessage);
            return;
        }
        ZstdPipeline.offerUpgrade(connection, () -> {
            FriendlyByteBuf prepare = PacketByteBufs.create();
            prepare.writeVarInt(1);
            return ServerPlayNetworking.createS2CPacket(INTEGRATED_PREPARE_ID, prepare);
        });
    }

    public static void sendServerHudSnapshot(ServerPlayer player, ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        if (snapshot == null || !ServerPlayNetworking.canSend(player, SERVER_HUD_ID)) {
            return;
        }
        if ("INTEGRATED".equals(snapshot.mode()) && !ServerPlayNetworking.canSend(player, INTEGRATED_PREPARE_ID)) {
            return;
        }
        FriendlyByteBuf buf = PacketByteBufs.create();
        encodeServerHudSnapshot(snapshot, buf);
        ServerPlayNetworking.send(player, SERVER_HUD_ID, buf);
    }


    public static void requestTrafficReport(String range) {
        FriendlyByteBuf request = PacketByteBufs.create();
        request.writeUtf(range, 16);
        ClientPlayNetworking.send(TRAFFIC_REPORT_REQUEST_ID, request);
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
