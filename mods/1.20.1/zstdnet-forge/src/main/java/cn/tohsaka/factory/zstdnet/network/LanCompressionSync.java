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
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 1048576;
    private static final int MAX_REPORT_BYTES = 1024 * 1024;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROTOCOL_VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(Zstdnet.MODID, "lan_compression"),
        () -> PROTOCOL_VERSION,
        version -> NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version) || PROTOCOL_VERSION.equals(version),
        version -> NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version) || PROTOCOL_VERSION.equals(version)
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
        CHANNEL.messageBuilder(ActivateMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ActivateMessage::encode)
            .decoder(ActivateMessage::decode)
            .consumerMainThread(ActivateMessage::handle)
            .add();
        CHANNEL.messageBuilder(ServerHudMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(ServerHudMessage::encode)
            .decoder(ServerHudMessage::decode)
            .consumerMainThread(ServerHudMessage::handle)
            .add();
        CHANNEL.messageBuilder(TrafficReportRequestMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder(TrafficReportRequestMessage::encode)
            .decoder(TrafficReportRequestMessage::decode)
            .consumerMainThread(TrafficReportRequestMessage::handle)
            .add();
        CHANNEL.messageBuilder(TrafficReportResponseMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(TrafficReportResponseMessage::encode)
            .decoder(TrafficReportResponseMessage::decode)
            .consumerMainThread(TrafficReportResponseMessage::handle)
            .add();
        CHANNEL.messageBuilder(UdpDirectPortsMessage.class, id++, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(UdpDirectPortsMessage::encode)
            .decoder(UdpDirectPortsMessage::decode)
            .consumerMainThread(UdpDirectPortsMessage::handle)
            .add();
    }

    public static void requestCompressionUpgrade(ServerPlayer player) {
        init();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PrepareMessage(LAN_THRESHOLD));
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
        init();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), ServerHudMessage.from(snapshot));
    }

    public static void sendUdpDirectPorts(ServerPlayer player, List<Integer> ports) {
        init();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new UdpDirectPortsMessage(ports));
    }

    public static void requestTrafficReport(String range) {
        init();
        CHANNEL.sendToServer(new TrafficReportRequestMessage(range));
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

    private record TrafficReportRequestMessage(String range) {
        private static TrafficReportRequestMessage decode(FriendlyByteBuf buf) {
            return new TrafficReportRequestMessage(buf.readUtf(16));
        }

        private static void encode(TrafficReportRequestMessage message, FriendlyByteBuf buf) {
            buf.writeUtf(message.range, 16);
        }

        private static void handle(TrafficReportRequestMessage message, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                TrafficReportResponseMessage response;
                if (!player.hasPermissions(2)) {
                    response = new TrafficReportResponseMessage(false, "需要服务器管理员权限才能导出流量报告。");
                } else {
                    try {
                        response = new TrafficReportResponseMessage(true, ServerProxyBootstrap.buildTrafficReport(message.range));
                    } catch (RuntimeException e) {
                        LOGGER.warn("[zstdnet-server] failed to build traffic report for {}: {}", player.getGameProfile().getName(), e.toString());
                        response = new TrafficReportResponseMessage(false, "服务器生成流量报告失败：" + e.getMessage());
                    }
                }
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), response);
            });
            context.setPacketHandled(true);
        }
    }

    private record TrafficReportResponseMessage(boolean success, String payload) {
        private static TrafficReportResponseMessage decode(FriendlyByteBuf buf) {
            return new TrafficReportResponseMessage(buf.readBoolean(), buf.readUtf(MAX_REPORT_BYTES));
        }

        private static void encode(TrafficReportResponseMessage message, FriendlyByteBuf buf) {
            buf.writeBoolean(message.success);
            buf.writeUtf(message.payload, MAX_REPORT_BYTES);
        }

        private static void handle(TrafficReportResponseMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> ClientProxyPublisher.acceptTrafficReportResponse(message.success, message.payload));
            supplier.get().setPacketHandled(true);
        }
    }

    private record UdpDirectPortsMessage(List<Integer> ports) {
        private static final int MAX_PORTS = 32;

        private UdpDirectPortsMessage {
            ports = ports == null ? List.of() : List.copyOf(ports);
        }

        private static UdpDirectPortsMessage decode(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_PORTS) {
                throw new IllegalArgumentException("invalid direct UDP port count " + count);
            }
            List<Integer> ports = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                int port = buf.readVarInt();
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("invalid direct UDP port " + port);
                }
                ports.add(port);
            }
            return new UdpDirectPortsMessage(ports);
        }

        private static void encode(UdpDirectPortsMessage message, FriendlyByteBuf buf) {
            buf.writeVarInt(message.ports.size());
            for (int port : message.ports) {
                buf.writeVarInt(port);
            }
        }

        private static void handle(UdpDirectPortsMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> ClientProxyPublisher.acceptUdpDirectPorts(message.ports));
            supplier.get().setPacketHandled(true);
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
    ) {
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

        private static ServerHudMessage decode(FriendlyByteBuf buf) {
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

        private static void encode(ServerHudMessage message, FriendlyByteBuf buf) {
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

        private static void handle(ServerHudMessage message, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().enqueueWork(() -> ClientProxyPublisher.acceptRemoteServerHudSnapshot(message.toSnapshot()));
            supplier.get().setPacketHandled(true);
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
