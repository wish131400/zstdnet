package cn.tohsaka.factory.zstdnet.server;

import cn.tohsaka.factory.zstdnet.core.stats.TrafficStatisticsService;
import cn.tohsaka.factory.zstdnet.core.stats.TrafficStats;
import cn.tohsaka.factory.zstdnet.coremod.ServerRealIpHooks;
import cn.tohsaka.factory.zstdnet.network.LanCompressionSync;
import cn.tohsaka.factory.zstdnet.mixin.ServerGamePacketListenerImplAccessor;
import com.mojang.logging.LogUtils;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ServerProxyBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final TrafficStats TRAFFIC_STATS = new TrafficStats();
    private static final TrafficStatisticsService TRAFFIC_HISTORY = new TrafficStatisticsService(
        ServerProxyConfigFile.path().getParent().resolve("zstdnet").resolve("stats"),
        ZoneId.systemDefault(),
        TRAFFIC_STATS
    );
    private static volatile boolean integratedMode;
    private static volatile int integratedListenPort = -1;
    private static volatile long lastHudSyncMillis;
    private static volatile long sampleAt;
    private static volatile long sampleRawUp;
    private static volatile long sampleRawDown;
    private static volatile long sampleWireUp;
    private static volatile long sampleWireDown;
    private static ServerHudSnapshot cachedSnapshot;

    private ServerProxyBootstrap() {
    }

    public static void addIntegratedRawUp(long bytes) {
        TRAFFIC_STATS.addRawUp(bytes);
    }

    public static void addIntegratedWireUp(long bytes) {
        TRAFFIC_STATS.addZstdUp(bytes);
    }

    public static void addIntegratedRawDown(long bytes) {
        TRAFFIC_STATS.addRawDown(bytes);
    }

    public static void addIntegratedWireDown(long bytes) {
        TRAFFIC_STATS.addZstdDown(bytes);
    }

    public static void addIntegratedConnection(int delta) {
        TRAFFIC_STATS.addConn(delta);
    }

    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        ServerRealIpHooks.configureDebugLogging(ServerProxyConfigFile.readDebug());
        ServerRealIpHooks.configureProxyProtocol(
            ServerProxyConfigFile.readTrustProxyProtocol(),
            ServerProxyConfigFile.readTrustedProxyIps()
        );
        ServerLifecycleEvents.SERVER_STARTED.register(ServerProxyBootstrap::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(ServerProxyBootstrap::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(ServerProxyBootstrap::onServerTick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerLoggedIn(handler.player));
        LOGGER.info("zstdnet server bootstrap initialized");
    }

    public static synchronized ServerHudSnapshot currentHudSnapshot() {
        if (!integratedMode || !ServerProxyConfigFile.readEnabled()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (cachedSnapshot != null && cachedSnapshot.listenPort() == integratedListenPort
                && now - sampleAt < 1000L) {
            return cachedSnapshot;
        }
        long rawUp = TRAFFIC_STATS.rawUpBytes();
        long rawDown = TRAFFIC_STATS.rawDownBytes();
        long wireUp = TRAFFIC_STATS.zstdUpBytes();
        long wireDown = TRAFFIC_STATS.zstdDownBytes();
        long elapsed = sampleAt == 0L ? 0L : Math.max(1L, now - sampleAt);
        long rawUpRate = elapsed == 0L ? 0L : rate(rawUp - sampleRawUp, elapsed);
        long rawDownRate = elapsed == 0L ? 0L : rate(rawDown - sampleRawDown, elapsed);
        long wireUpRate = elapsed == 0L ? 0L : rate(wireUp - sampleWireUp, elapsed);
        long wireDownRate = elapsed == 0L ? 0L : rate(wireDown - sampleWireDown, elapsed);
        sampleAt = now;
        sampleRawUp = rawUp;
        sampleRawDown = rawDown;
        sampleWireUp = wireUp;
        sampleWireDown = wireDown;
        long raw = rawUp + rawDown;
        long wire = wireUp + wireDown;
        cachedSnapshot = new ServerHudSnapshot(
            "INTEGRATED", "0.0.0.0", integratedListenPort,
            raw, wire, rawUp, rawDown, wireUp, wireDown,
            rawUpRate, rawDownRate, wireUpRate, wireDownRate,
            rawUpRate + rawDownRate, wireUpRate + wireDownRate,
            raw == 0L ? 0.0D : (double) wire * 100.0D / (double) raw,
            TRAFFIC_STATS.activeConnections()
        );
        return cachedSnapshot;
    }

    private static long rate(long bytes, long elapsedMillis) {
        return bytes <= 0L ? 0L : (long) Math.min(Long.MAX_VALUE, (double) bytes * 1000.0D / elapsedMillis);
    }

    public static String buildTrafficReport(String range) {
        ServerHudSnapshot snapshot = currentHudSnapshot();
        String mode = snapshot == null ? "inactive" : snapshot.mode();
        String listen = snapshot == null ? "" : snapshot.listenHost() + ":" + snapshot.listenPort();
        return TRAFFIC_HISTORY.buildReportJson(range, mode, listen);
    }

    private static void onServerStarted(MinecraftServer server) {
        TRAFFIC_HISTORY.startSession();
        try {
            if (ServerProxyConfigFile.ensureExists()) {
                LOGGER.info("[zstdnet-server] created server config at {}", ServerProxyConfigFile.path());
            }
            ServerProxyConfigFile.compactIntegratedConfig();
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] could not update server config: {}", e.toString());
        }
        ServerRealIpHooks.configureFloodGuard(ServerProxyConfigFile.createFloodGuard());
        ServerRealIpHooks.configureDebugLogging(ServerProxyConfigFile.readDebug());
        ServerRealIpHooks.configureProxyProtocol(
            ServerProxyConfigFile.readTrustProxyProtocol(),
            ServerProxyConfigFile.readTrustedProxyIps()
        );
        if (server.isDedicatedServer()) {
            integratedMode = true;
            integratedListenPort = server.getPort();
            LOGGER.info("[zstdnet-server] zstd transport ready on Minecraft port {} (online-mode={}).",
                integratedListenPort, server.usesAuthentication());
        }
    }

    private static void onServerStopping(MinecraftServer server) {
        integratedMode = false;
        integratedListenPort = -1;
        lastHudSyncMillis = 0L;
        sampleAt = 0L;
        cachedSnapshot = null;
        ServerRealIpHooks.configureFloodGuard(null);
        TRAFFIC_HISTORY.stopSession();
    }

    private static void onServerTick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!server.isDedicatedServer()) {
            boolean published = server.isPublished() && server.getPort() > 0;
            integratedMode = published;
            integratedListenPort = published ? server.getPort() : -1;
        }
        if (integratedMode) {
            syncServerHudSnapshot(server);
        }
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

    private static void onPlayerLoggedIn(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null && !server.isDedicatedServer() && server.isPublished()) {
            integratedMode = true;
            integratedListenPort = server.getPort();
        }
        if (integratedMode && ServerProxyConfigFile.readEnabled() && !((ServerGamePacketListenerImplAccessor) player.connection).zstdnet$getConnection().isMemoryConnection()) {
            LanCompressionSync.requestIntegratedUpgrade(player);
        }
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
