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

package cn.tohsaka.factory.zstdnet.server;

import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;
import cn.tohsaka.factory.zstdnet.mixin.ServerGamePacketListenerImplAccessor;
import cn.tohsaka.factory.zstdnet.network.LanCompressionSync;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side bootstrap that wires the proxy runtime into Fabric lifecycle callbacks.
 */
public final class ServerProxyBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final ServerProxyRuntime RUNTIME = new ServerProxyRuntime();
    private static volatile int publishedLanPort = -1;
    private static volatile int activeLanPort = -1;
    private static volatile long lastHudSyncMillis;

    private ServerProxyBootstrap() {
    }

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(ServerProxyBootstrap::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerProxyBootstrap::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(ServerProxyBootstrap::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerLoggedIn(handler.player));
        LOGGER.info("zstdnet server bootstrap initialized");
    }

    public static ServerHudSnapshot currentHudSnapshot() {
        ServerProxyRuntime.HudSnapshot snapshot = RUNTIME.hudSnapshot();
        if (snapshot == null) {
            return null;
        }
        return new ServerHudSnapshot(
            snapshot.modeName(),
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

    private static void onServerStarted(MinecraftServer server) {
        if (!server.isDedicatedServer()) {
            return;
        }
        DedicatedServerAutoPort.AutoPortPlan plan = DedicatedServerAutoPort.activePlan();
        if (plan != null) {
            LOGGER.info(
                "[zstdnet-server] public entry is {}:{}, backend was reassigned to {}:{}",
                plan.listenHost(),
                plan.listenPort(),
                plan.targetHost(),
                plan.targetPort()
            );
        }
        RUNTIME.start(server.getPort());
    }

    private static void onServerStopping(MinecraftServer server) {
        publishedLanPort = -1;
        activeLanPort = -1;
        lastHudSyncMillis = 0L;
        RUNTIME.stop();
        DedicatedServerAutoPort.clear();
    }

    private static void onServerTick(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            if (RUNTIME.configChangedOnDisk()) {
                LOGGER.info("[zstdnet-server] config changed on disk, reloading proxy.");
                RUNTIME.stop();
                RUNTIME.start(server.getPort());
            }
            syncServerHudSnapshot(server);
            return;
        }

        boolean published = server.isPublished();
        int lanPort = published ? server.getPort() : -1;

        if (published && lanPort > 0) {
            forceDisableLanAuthentication(server);
            if (publishedLanPort != lanPort || RUNTIME.configChangedOnDisk()) {
                publishedLanPort = lanPort;
                if (RUNTIME.isRunning()) {
                    LOGGER.info("[zstdnet-server] config/LAN state changed, reloading proxy.");
                    RUNTIME.stop();
                }
                RUNTIME.startLan(lanPort);
                activeLanPort = RUNTIME.isLanMode() ? lanPort : -1;
                if (activeLanPort > 0) {
                    notifyLanProxyReady(server, lanPort);
                    LOGGER.info("[zstdnet-server] LAN world published on {}, zstd proxy armed.", lanPort);
                } else {
                    LOGGER.warn("[zstdnet-server] LAN world published on {}, but zstd proxy did not start. Check zstdnet-server.properties.", lanPort);
                }
            }
            return;
        }

        if (RUNTIME.isLanMode()) {
            LOGGER.info("[zstdnet-server] LAN world is no longer published, stopping zstd proxy.");
            RUNTIME.stop();
        }
        publishedLanPort = -1;
        activeLanPort = -1;
    }

    private static void syncServerHudSnapshot(MinecraftServer server) {
        long now = System.currentTimeMillis();
        if (now - lastHudSyncMillis < 1000L) {
            return;
        }
        lastHudSyncMillis = now;

        ServerHudSnapshot snapshot = currentHudSnapshot();
        if (snapshot == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            LanCompressionSync.sendServerHudSnapshot(player, snapshot);
        }
    }

    private static void notifyLanProxyReady(MinecraftServer server, int lanPort) {
        ServerProxyRuntime.HudSnapshot snapshot = RUNTIME.hudSnapshot();
        if (snapshot == null) {
            return;
        }
        Component message = Component.translatable(
            "zstdnet.singleplayer.lan_ready",
            copyableZstdPort(snapshot.listenPort()),
            lanPort
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
        LOGGER.info("[zstdnet-server] LAN ready: zstd listen port={}, game port={}", snapshot.listenPort(), lanPort);
    }

    private static Component copyableZstdPort(int port) {
        String text = String.valueOf(port);
        return Component.literal(text).withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("zstdnet.singleplayer.lan_ready.copy_zstd"))));
    }

    private static void forceDisableLanAuthentication(MinecraftServer server) {
        if (server.usesAuthentication()) {
            server.setUsesAuthentication(false);
            LOGGER.info("[zstdnet-server] LAN mode detected, disabled online authentication by default.");
        }
    }

    private static void onPlayerLoggedIn(ServerPlayer player) {
        if (!RUNTIME.protectsBackendLogin()) {
            return;
        }

        SocketAddress remoteAddress = player.connection.getRemoteAddress();
        var connection = ((ServerGamePacketListenerImplAccessor) player.connection).zstdnet$getConnection();
        if (isLoopback(remoteAddress) || ServerRealIpHooks.isForwardedConnection(connection)) {
            if (activeLanPort > 0 && !connection.isMemoryConnection()) {
                LanCompressionSync.requestCompressionUpgrade(player);
            }
            return;
        }

        LOGGER.warn("[server] rejected direct backend login from {}", remoteAddress);
        player.connection.disconnect(Component.literal(ServerProxyRuntime.ZSTD_ADDRESS_HINT));
    }

    private static boolean isLoopback(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)) {
            return false;
        }

        InetAddress ip = inet.getAddress();
        if (ip == null) {
            return false;
        }
        return ip.isLoopbackAddress() || ip.isAnyLocalAddress();
    }

    public record ServerHudSnapshot(
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
    }
}
