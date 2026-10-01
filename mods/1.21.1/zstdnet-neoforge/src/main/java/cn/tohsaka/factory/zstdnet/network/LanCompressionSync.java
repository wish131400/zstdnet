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
import cn.tohsaka.factory.zstdnet.Zstdnet;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import cn.tohsaka.factory.zstdnet.core.transport.ZstdPipeline;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import java.util.ArrayList;
import java.util.List;
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
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LanCompressionSync {
    public static final int LAN_THRESHOLD = 256;
    private static final int MAX_REPORT_BYTES = 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(LanCompressionSync.class);
    private static final String PROTOCOL_VERSION = "2";
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
        PayloadRegistrar transport = registrar.executesOn(HandlerThread.NETWORK);
        transport.playToClient(PrepareMessage.TYPE, PrepareMessage.STREAM_CODEC, PrepareMessage::handle);
        transport.playToServer(ReadyMessage.TYPE, ReadyMessage.STREAM_CODEC, ReadyMessage::handle);
        transport.playToClient(ActivateMessage.TYPE, ActivateMessage.STREAM_CODEC, ActivateMessage::handle);
        transport.playToClient(IntegratedPrepareMessage.TYPE, IntegratedPrepareMessage.STREAM_CODEC, IntegratedPrepareMessage::handle);
        transport.playToServer(IntegratedReadyMessage.TYPE, IntegratedReadyMessage.STREAM_CODEC, IntegratedReadyMessage::handle);
        registrar.playToClient(ServerHudMessage.TYPE, ServerHudMessage.STREAM_CODEC, ServerHudMessage::handle);
        registrar.playToServer(TrafficReportRequestMessage.TYPE, TrafficReportRequestMessage.STREAM_CODEC, TrafficReportRequestMessage::handle);
        registrar.playToClient(TrafficReportResponseMessage.TYPE, TrafficReportResponseMessage.STREAM_CODEC, TrafficReportResponseMessage::handle);
    }

    public static void requestCompressionUpgrade(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new PrepareMessage(LAN_THRESHOLD));
        LOGGER.info(
            "[zstdnet-server] requested LAN compression threshold {} for {}.",
            LAN_THRESHOLD,
            player.getGameProfile().getName()
        );
    }

    public static void requestIntegratedUpgrade(ServerPlayer player) {
        Connection connection = player.connection.getConnection();
        if (!NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, IntegratedPrepareMessage.ID)) {
            return;
        }
        if (!ZstdPipeline.canUpgrade(connection)) {
            ZstdPipeline.notifyKryptonFallback(connection, player::sendSystemMessage);
            return;
        }
        ZstdPipeline.offerUpgrade(connection, () -> new ClientboundCustomPayloadPacket(new IntegratedPrepareMessage(1)));
    }

    private record IntegratedPrepareMessage(int version) implements CustomPacketPayload {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Zstdnet.MODID, "integrated_prepare");
        private static final Type<IntegratedPrepareMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, IntegratedPrepareMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, IntegratedPrepareMessage::version, IntegratedPrepareMessage::new
        );

        @Override
        public Type<IntegratedPrepareMessage> type() {
            return TYPE;
        }

        private static void handle(IntegratedPrepareMessage message, IPayloadContext context) {
            if (message.version != 1) {
                return;
            }
            prepareClientTransport(context, new IntegratedReadyMessage(1));
        }
    }

    private static void prepareClientTransport(IPayloadContext context, CustomPacketPayload ready) {
        Connection connection = context.connection();
        if (ZstdPipeline.canUpgrade(connection)) {
            ZstdPipeline.installClientDecoder(connection, () -> ZstdPipeline.sendUpgradeReady(connection,
                new ServerboundCustomPayloadPacket(ready)));
        } else {
            context.enqueueWork(() -> ZstdPipeline.notifyKryptonFallback(connection, context.player()::sendSystemMessage));
        }
    }

    private static void activateIntegrated(Connection connection) {
        if (!NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, IntegratedPrepareMessage.ID)
            || !ZstdPipeline.consumeUpgradeOffer(connection)) {
            return;
        }
        ZstdPipeline.installDecoder(connection,
            ServerProxyBootstrap::addIntegratedRawUp,
            ServerProxyBootstrap::addIntegratedWireUp,
            () -> ZstdPipeline.sendUpgradeActivation(connection, new ClientboundCustomPayloadPacket(new ActivateMessage(-1)),
                ServerProxyConfigFile.readLevel(),
                ServerProxyBootstrap::addIntegratedRawDown,
                ServerProxyBootstrap::addIntegratedWireDown,
                () -> ServerProxyBootstrap.addIntegratedConnection(1),
                () -> ServerProxyBootstrap.addIntegratedConnection(-1)
            ));
    }

    private record IntegratedReadyMessage(int version) implements CustomPacketPayload {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Zstdnet.MODID, "integrated_ready");
        private static final Type<IntegratedReadyMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, IntegratedReadyMessage> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, IntegratedReadyMessage::version, IntegratedReadyMessage::new
        );

        @Override
        public Type<IntegratedReadyMessage> type() {
            return TYPE;
        }

        private static void handle(IntegratedReadyMessage message, IPayloadContext context) {
            if (message.version == 1) {
                ReadyMessage.handle(new ReadyMessage(-1), context);
            }
        }
    }

    public static void sendServerHudSnapshot(ServerPlayer player, ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        if (snapshot == null || !NetworkRegistry.hasChannel(player.connection.getConnection(), ConnectionProtocol.PLAY, ServerHudMessage.ID)) {
            return;
        }
        if ("INTEGRATED".equals(snapshot.mode())
            && !NetworkRegistry.hasChannel(player.connection.getConnection(), ConnectionProtocol.PLAY, IntegratedPrepareMessage.ID)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, ServerHudMessage.from(snapshot));
    }


    public static void requestTrafficReport(String range) {
        PacketDistributor.sendToServer(new TrafficReportRequestMessage(range));
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
            if (message.threshold == -1) {
                prepareClientTransport(context, new ReadyMessage(-1));
            } else {
                context.reply(new ReadyMessage(message.threshold));
            }
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
            if (message.threshold == -1) {
                activateIntegrated(context.connection());
                return;
            }
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
            if (message.threshold == -1) {
                ZstdPipeline.installClientEncoder(context.connection(), ClientConfig.getLevel());
            } else {
                context.connection().setupCompression(message.threshold, false);
            }
        }
    }

    private record TrafficReportRequestMessage(String range) implements CustomPacketPayload {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Zstdnet.MODID, "traffic_report_request");
        private static final Type<TrafficReportRequestMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, TrafficReportRequestMessage> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> buf.writeUtf(message.range, 16),
            buf -> new TrafficReportRequestMessage(buf.readUtf(16))
        );

        @Override
        public Type<TrafficReportRequestMessage> type() {
            return TYPE;
        }

        private static void handle(TrafficReportRequestMessage message, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player)) {
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
                PacketDistributor.sendToPlayer(player, response);
            });
        }
    }

    private record TrafficReportResponseMessage(boolean success, String payload) implements CustomPacketPayload {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Zstdnet.MODID, "traffic_report_response");
        private static final Type<TrafficReportResponseMessage> TYPE = new Type<>(ID);
        private static final StreamCodec<RegistryFriendlyByteBuf, TrafficReportResponseMessage> STREAM_CODEC = StreamCodec.of(
            (buf, message) -> {
                buf.writeBoolean(message.success);
                buf.writeUtf(message.payload, MAX_REPORT_BYTES);
            },
            buf -> new TrafficReportResponseMessage(buf.readBoolean(), buf.readUtf(MAX_REPORT_BYTES))
        );

        @Override
        public Type<TrafficReportResponseMessage> type() {
            return TYPE;
        }

        private static void handle(TrafficReportResponseMessage message, IPayloadContext context) {
            context.enqueueWork(() -> ClientProxyPublisher.acceptTrafficReportResponse(message.success, message.payload));
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

        private static void handle(ServerHudMessage message, IPayloadContext context) {
            context.enqueueWork(() -> ClientProxyPublisher.acceptRemoteServerHudSnapshot(message.toSnapshot()));
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
