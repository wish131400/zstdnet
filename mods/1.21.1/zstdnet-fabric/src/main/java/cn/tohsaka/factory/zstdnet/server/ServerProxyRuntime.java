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

import cn.tohsaka.factory.zstdnet.core.io.CountingInputStream;
import cn.tohsaka.factory.zstdnet.core.io.CountingOutputStream;
import cn.tohsaka.factory.zstdnet.core.limit.TokenBucketLimiter;
import cn.tohsaka.factory.zstdnet.core.protocol.ByteArrayOps;
import cn.tohsaka.factory.zstdnet.core.protocol.PacketIo;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntCodec;
import cn.tohsaka.factory.zstdnet.core.protocol.VarIntRead;
import cn.tohsaka.factory.zstdnet.core.stats.TrafficStats;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内置服务端代理运行时模块。
 * <p>
 * 负责：
 * 1) 读取/生成配置并监听入口端口；
 * 2) 双向转发（客户端->后端解压，后端->客户端压缩）；
 * 3) 限流、防刷与连接统计；
 * 4) 管理线程池与生命周期启停。
 */
final class ServerProxyRuntime {
    static final String ZSTD_ADDRESS_HINT = "当前服务器启用了 ZSTD 连接，请联系服务器管理员获取正确的连接地址。";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final byte[] PROXY_V2_SIGNATURE = new byte[]{
        0x0d, 0x0a, 0x0d, 0x0a, 0x00, 0x0d, 0x0a, 0x51, 0x55, 0x49, 0x54, 0x0a
    };
    private static final int DEFAULT_MINECRAFT_PORT = 25565;
    private static final int DEFAULT_LISTEN_PORT = 35565;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
    private static final int DEFAULT_VOICE_CHAT_PORT = 24454;
    private static final int DEFAULT_VOICE_CHAT_LISTEN_PORT = 24455;
    private static final int DEFAULT_ZSTD_LEVEL = 9;
    private static final int DEFAULT_MAX_CONN_PER_IP = 9999;
    private static final int DEFAULT_MAX_REQ_PER_WINDOW = 50;
    private static final int DEFAULT_BURST_BYTES = 256 * 1024;
    private static final Duration DEFAULT_BAN_DURATION = Duration.ofMinutes(1);
    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ZERO;
    private static final String DEFAULT_TRUSTED_PROXY_IPS = "127.0.0.1,::1,0:0:0:0:0:0:0:1";
    private static final int CLIENT_PEEK_BUFFER = 4096;
    private static final int MAX_HANDSHAKE_PACKET_SIZE = 2048;
    private static final int MAX_PROXY_V2_PAYLOAD_SIZE = 216;

    private final Object lifecycleLock = new Object();

    private volatile boolean running;
    private ServerSocket listener;
    private Thread acceptThread;
    private ExecutorService workers;
    private ScheduledExecutorService statsTicker;
    private FloodGuard guard;
    private TrafficStats stats;
    private ProxyConfig cfg;
    private TokenBucketLimiter globalLimiter;
    private RuntimeMode runtimeMode;
    private volatile HudSnapshot latestHudSnapshot;
    private volatile long loadedConfigLastModified = Long.MIN_VALUE;
    private List<UdpForwarder> udpForwarders = List.of();

    /**
     * 启动运行时。
     */
    void start(int mcServerPort) {
        start(mcServerPort, RuntimeMode.DEDICATED);
    }

    void startLan(int lanPort) {
        start(lanPort, RuntimeMode.LAN);
    }

    private void start(int mcServerPort, RuntimeMode mode) {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }

            Path configPath = ServerProxyConfigFile.path();
            ProxyConfig loaded = loadOrCreateConfig(configPath, mcServerPort, mode);
            if (loaded == null) {
                return;
            }
            if (!loaded.enabled) {
                LOGGER.warn("[zstdnet-server] config exists but enabled=false, skip start. File: {}", configPath);
                LOGGER.warn("[zstdnet-server] set enabled=true after checking listen/target.");
                return;
            }

            if (mode == RuntimeMode.DEDICATED && loaded.autoTakeover) {
                DedicatedServerAutoPort.AutoPortPlan plan = DedicatedServerAutoPort.activePlan();
                if (plan == null) {
                    LOGGER.error("[zstdnet-server] auto_takeover=true but no dedicated auto-port plan was prepared. Restart the server and check coremod logs.");
                    return;
                }
                loaded = loaded.withEndpoints(
                    new HostPort(plan.listenHost(), plan.listenPort()),
                    new HostPort(plan.targetHost(), plan.targetPort())
                );
            }

            if (mode == RuntimeMode.LAN) {
                loaded = loaded.withTarget(new HostPort("127.0.0.1", mcServerPort));
                loaded = loaded.withLanVoiceDefaults(mcServerPort);
            }

            BindResult bindResult = bindListener(loaded, mode);
            if (bindResult == null) {
                return;
            }
            listener = bindResult.listener();
            loaded = bindResult.config();

            this.cfg = loaded;
            this.stats = new TrafficStats();
            this.guard = new FloodGuard(loaded);
            this.workers = Executors.newCachedThreadPool(new NamedFactory("zstdsrv-worker"));
            this.statsTicker = Executors.newSingleThreadScheduledExecutor(new NamedFactory("zstdsrv-stats"));
            this.globalLimiter = TokenBucketLimiter.create(loaded.maxRateGlobalBps, loaded.burstBytes);
            this.runtimeMode = mode;
            this.latestHudSnapshot = new HudSnapshot(mode, loaded.listen.host, loaded.listen.port, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0.0D, 0);
            this.running = true;

            startStatsPrinter();
            acceptThread = new Thread(this::acceptLoop, "zstdsrv-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();

            startUdpForwarders(loaded);

            LOGGER.info("[zstdnet-server] started: mode={} listen={} target={}", mode.name().toLowerCase(Locale.ROOT), loaded.listen, loaded.target);
            if (mode == RuntimeMode.LAN) {
                LOGGER.info("[zstdnet-server] LAN host detected. Point your tunnel to {} instead of the raw LAN port {}.", loaded.listen, mcServerPort);
            }
            LOGGER.info("[zstdnet-server] guard: max_conn={} max_req={} window={} ban={}",
                loaded.maxConnPerIp, loaded.maxReqPerWindow, loaded.window, loaded.banDuration);
            LOGGER.info("[zstdnet-server] tuning: flush={} idle_timeout={} rate_per_conn={}B/s rate_global={}B/s burst={}B",
                loaded.flushInterval, loaded.idleTimeout, loaded.maxRatePerConnBps, loaded.maxRateGlobalBps, loaded.burstBytes);
            if (loaded.autoTakeover && mode == RuntimeMode.DEDICATED) {
                LOGGER.info("[zstdnet-server] auto takeover active: public_entry={} backend={}", loaded.listen, loaded.target);
            }
            LOGGER.info("[zstdnet-server] raw vanilla login passthrough: disabled");
        }
    }

    private BindResult bindListener(ProxyConfig config, RuntimeMode mode) {
        if (mode == RuntimeMode.LAN && isReservedLanListenPort(config, config.listen.port)) {
            LOGGER.warn("[zstdnet-server] LAN listen port {} is reserved by the current LAN session, trying the next available port.", config.listen);
        } else {
            try {
                return bindListenerOnPort(config, config.listen.port);
            } catch (IOException e) {
                if (mode != RuntimeMode.LAN) {
                    LOGGER.error("[zstdnet-server] bind failed on {}: {}", config.listen, e.toString());
                    return null;
                }
                LOGGER.warn("[zstdnet-server] LAN listen port {} is unavailable, trying the next available port: {}", config.listen, e.toString());
            }
        }

        for (int port = Math.max(1024, config.listen.port + 1); port <= MAX_PORT; port++) {
            BindResult result = tryBindLanListener(config, port);
            if (result != null) {
                return result;
            }
        }
        for (int port = 1024; port < Math.max(1024, config.listen.port); port++) {
            BindResult result = tryBindLanListener(config, port);
            if (result != null) {
                return result;
            }
        }
        LOGGER.error("[zstdnet-server] no available LAN listen port found for host {}", config.listen.host);
        return null;
    }

    private BindResult tryBindLanListener(ProxyConfig config, int port) {
        if (isReservedLanListenPort(config, port)) {
            return null;
        }
        try {
            BindResult result = bindListenerOnPort(config, port);
            LOGGER.info("[zstdnet-server] LAN listen port changed from {} to {} because the preferred port was unavailable.", config.listen.port, port);
            return result;
        } catch (IOException ignored) {
            return null;
        }
    }

    private BindResult bindListenerOnPort(ProxyConfig config, int port) throws IOException {
        ProxyConfig resolved = config.withEndpoints(new HostPort(config.listen.host, port), config.target);
        ServerSocket socket = new ServerSocket();
        try {
            socket.bind(resolved.listen.toAddress());
            return new BindResult(socket, resolved);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        }
    }

    private boolean isReservedLanListenPort(ProxyConfig config, int port) {
        return port == config.target.port;
    }

    private int parseOptionalPort(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            return HostPort.parse(raw).port;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    /**
     * 停止运行时并回收资源。
     */
    void stop() {
        synchronized (lifecycleLock) {
            if (!running) {
                return;
            }
            running = false;
            stopUdpForwarders();
            closeQuietly(listener);
            listener = null;
            if (acceptThread != null) {
                try {
                    acceptThread.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            acceptThread = null;
            shutdownQuietly(statsTicker);
            shutdownQuietly(workers);
            statsTicker = null;
            workers = null;
            guard = null;
            stats = null;
            cfg = null;
            globalLimiter = null;
            runtimeMode = null;
            latestHudSnapshot = null;
            LOGGER.info("[zstdnet-server] stopped");
        }
    }

    /**
     * 接收入口连接并交给工作线程处理。
     */
    boolean isRunning() {
        return running;
    }

    boolean protectsBackendLogin() {
        return running && runtimeMode == RuntimeMode.DEDICATED;
    }

    boolean isLanMode() {
        return running && runtimeMode == RuntimeMode.LAN;
    }

    HudSnapshot hudSnapshot() {
        if (!running || stats == null || cfg == null || runtimeMode == null) {
            return latestHudSnapshot;
        }
        HudSnapshot previous = latestHudSnapshot;
        long rawUpRate = previous == null ? 0L : previous.rawUpRate();
        long rawDownRate = previous == null ? 0L : previous.rawDownRate();
        long zstdUpRate = previous == null ? 0L : previous.zstdUpRate();
        long zstdDownRate = previous == null ? 0L : previous.zstdDownRate();
        return buildHudSnapshot(rawUpRate, rawDownRate, zstdUpRate, zstdDownRate);
    }

    boolean configChangedOnDisk() {
        Path path = ServerProxyConfigFile.path();
        long current = readLastModified(path);
        return current > 0L && current != loadedConfigLastModified;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = listener.accept();
                workers.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running) {
                    LOGGER.warn("[zstdnet-server] accept error: {}", e.toString());
                }
            } catch (Exception e) {
                if (running) {
                    LOGGER.warn("[zstdnet-server] accept unexpected error: {}", e.toString());
                }
            }
        }
    }

    /**
     * 处理单个客户端连接，建立到后端连接后执行双向转发。
     */
    private void handleClient(Socket client) {
        stats.addConn(1);
        String remoteIp = sourceIp(client.getRemoteSocketAddress());
        String guardIp = remoteIp;

        try (Socket clientSocket = client) {
            PushbackInputStream pushIn = new PushbackInputStream(clientSocket.getInputStream(), CLIENT_PEEK_BUFFER);
            ProxyInfo proxyInfo = parseProxyProtocolV2(pushIn);
            String forwardedSourceIp = resolveForwardedSourceIp(remoteIp, proxyInfo);
            if (forwardedSourceIp == null) {
                return;
            }

            if (!guard.begin(guardIp)) {
                LOGGER.warn("[server] blocked {} by flood guard", guardIp);
                return;
            }

            DetectedClientMode clientMode = detectClientMode(pushIn);
            if (clientMode.mode == ClientMode.RAW_LOGIN) {
                LOGGER.warn("[server] rejected raw login attempt from {} on zstd-only entry", guardIp);
                sendLoginDisconnect(
                    clientSocket,
                    """
                    当前服务器启用了 ZSTD 连接，请联系服务器管理员获取正确的连接方式。
                    """
                );
                return;
            }

            try (Socket upstream = new Socket()) {
                try {
                    upstream.connect(cfg.target.toAddress(), 5000);
                } catch (IOException dialErr) {
                    LOGGER.warn("[server] Remote {} Connect Error: {}", clientSocket.getRemoteSocketAddress(), dialErr.getMessage());
                    return;
                }

                upstream.setTcpNoDelay(true);
                clientSocket.setTcpNoDelay(true);
                // Client->server play traffic can legitimately stay idle for a while.
                // Applying SO_TIMEOUT there causes false disconnects in LAN/Forge sessions.
                applyReadTimeout(upstream, cfg.idleTimeout);
                TokenBucketLimiter perConnLimiter = TokenBucketLimiter.create(cfg.maxRatePerConnBps, cfg.burstBytes);

                if (clientMode.mode == ClientMode.RAW_STATUS) {
                    forwardRawPassthrough(clientSocket, pushIn, upstream, clientMode.initialWireData, stats);
                    return;
                }

                final String backendSourceIp = forwardedSourceIp;
                Future<Exception> c2s = workers.submit(() -> {
                    try {
                        forwardDecompress(upstream, pushIn, stats, backendSourceIp);
                        return null;
                    } catch (Exception ex) {
                        return ex;
                    } finally {
                        closeWrite(upstream);
                    }
                });

                Future<Exception> s2c = workers.submit(() -> {
                    try {
                        forwardCompress(clientSocket.getOutputStream(), upstream, cfg.level, cfg.flushInterval, stats, perConnLimiter, globalLimiter);
                        return null;
                    } catch (Exception ex) {
                        return ex;
                    } finally {
                        closeWrite(clientSocket);
                    }
                });

                Exception err1 = null;
                Exception err2 = null;
                while (!c2s.isDone() && !s2c.isDone()) {
                    Thread.sleep(20L);
                }

                if (c2s.isDone()) {
                    err1 = c2s.get();
                }
                if (s2c.isDone()) {
                    err2 = s2c.get();
                }

                // One direction ended: close both sockets so the other direction exits quickly too.
                closeSocket(clientSocket);
                closeSocket(upstream);

                if (!c2s.isDone()) {
                    err1 = c2s.get();
                }
                if (!s2c.isDone()) {
                    err2 = s2c.get();
                }
                if (isRealPipeErr(err1)) {
                    LOGGER.warn("pipe error source={} dir=client->mc: {}", guardIp, err1.toString());
                }
                if (isRealPipeErr(err2)) {
                    LOGGER.warn("pipe error source={} dir=mc->client: {}", guardIp, err2.toString());
                }
            } finally {
                guard.end(guardIp);
            }
        } catch (Exception ex) {
            if (isRealPipeErr(ex)) {
                LOGGER.warn("[server] connection error source={} remote={}: {}", guardIp, remoteIp, ex.toString());
            }
        } finally {
            stats.addConn(-1);
        }
    }

    private String resolveForwardedSourceIp(String remoteIp, ProxyInfo proxyInfo) {
        if (!cfg.trustProxyProtocol) {
            return remoteIp;
        }

        if (!isTrustedProxyPeer(remoteIp)) {
            LOGGER.warn(
                "[server] rejected PROXY protocol connection from untrusted peer {}. trusted_proxy_ips={}",
                remoteIp,
                String.join(",", cfg.trustedProxyIps)
            );
            return null;
        }

        if (!proxyInfo.valid || proxyInfo.sourceIp == null || proxyInfo.sourceIp.isBlank()) {
            LOGGER.warn("[server] rejected trusted PROXY protocol peer {} without a valid PROXY v2 header", remoteIp);
            return null;
        }

        LOGGER.debug("[server] trusted PROXY protocol source {} via {}", proxyInfo.sourceIp, remoteIp);
        return proxyInfo.sourceIp;
    }

    private boolean isTrustedProxyPeer(String remoteIp) {
        return remoteIp != null && cfg.trustedProxyIps.contains(remoteIp.trim());
    }

    /**
     * 客户端 -> 后端：zstd 解压后透传。
     */
    /**
     * Detect whether the public entry is receiving a raw status ping or a zstd-wrapped play/login stream.
     */
    private DetectedClientMode detectClientMode(PushbackInputStream in) throws IOException {
        byte[] firstPacketWire = tryReadPacketWire(in, 1500);
        if (firstPacketWire == null || firstPacketWire.length == 0) {
            return DetectedClientMode.zstd();
        }

        byte[] firstPacket = PacketIo.extractPacketPayload(firstPacketWire);
        Integer nextState = extractHandshakeNextState(firstPacket);
        if (nextState != null && nextState == 1) {
            byte[] secondPacketWire = tryReadPacketWire(in, 1500);
            if (secondPacketWire != null && secondPacketWire.length > 0) {
                byte[] secondPacket = PacketIo.extractPacketPayload(secondPacketWire);
                if (isStatusRequestPacket(secondPacket)) {
                    return new DetectedClientMode(
                        ClientMode.RAW_STATUS,
                        ByteArrayOps.concat(firstPacketWire, secondPacketWire)
                    );
                }
                in.unread(secondPacketWire);
            }
        } else if (nextState != null && nextState == 2) {
            byte[] secondPacketWire = tryReadPacketWire(in, 1500);
            if (secondPacketWire != null && secondPacketWire.length > 0) {
                byte[] secondPacket = PacketIo.extractPacketPayload(secondPacketWire);
                if (isLoginStartPacket(secondPacket)) {
                    return new DetectedClientMode(ClientMode.RAW_LOGIN, ByteArrayOps.concat(firstPacketWire, secondPacketWire));
                }
                in.unread(secondPacketWire);
            }
        }

        in.unread(firstPacketWire);
        return DetectedClientMode.zstd();
    }

    private void forwardRawPassthrough(
        Socket clientSocket,
        InputStream clientIn,
        Socket upstream,
        byte[] initialWireData,
        TrafficStats stats
    ) throws Exception {
        OutputStream upstreamOut = upstream.getOutputStream();
        upstreamOut.write(initialWireData);
        upstreamOut.flush();
        addRawPassthroughStats(initialWireData.length, stats, true);

        Future<?> upstreamWriter = workers.submit(() -> {
            try {
                streamRaw(clientIn, upstreamOut, stats, true);
            } catch (Exception ignored) {
            } finally {
                closeWrite(upstream);
            }
        });

        Future<?> downstreamWriter = workers.submit(() -> {
            try {
                streamRaw(upstream.getInputStream(), clientSocket.getOutputStream(), stats, false);
            } catch (Exception ignored) {
            } finally {
                closeWrite(clientSocket);
            }
        });

        upstreamWriter.get();
        downstreamWriter.get();
    }

    /**
     * Client -> backend: decompress zstd traffic before forwarding to Minecraft.
     */
    private void forwardDecompress(Socket dst, InputStream src, TrafficStats stats, String sourceIp) throws IOException {
        try (ZstdInputStream zstdIn = new ZstdInputStream(new CountingInputStream(src, stats::addZstdUp))) {
            OutputStream dstOut = dst.getOutputStream();
            byte[] firstPacket = PacketIo.readPacket(zstdIn);
            if (firstPacket.length > 0) {
                byte[] forwardedHandshake = appendForwardedIpToHandshake(firstPacket, sourceIp);
                PacketIo.writePacket(dstOut, forwardedHandshake);
                stats.addRawUp(VarIntCodec.encode(forwardedHandshake.length).length + forwardedHandshake.length);
            }

            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = zstdIn.read(buf)) >= 0) {
                if (n > 0) {
                    dstOut.write(buf, 0, n);
                    stats.addRawUp(n);
                }
            }
        }
    }

    private byte[] appendForwardedIpToHandshake(byte[] handshakePayload, String sourceIp) {
        if (handshakePayload == null || handshakePayload.length == 0 || sourceIp == null || sourceIp.isBlank()) {
            return handshakePayload;
        }

        VarIntRead packetId = VarIntCodec.read(handshakePayload, 0, handshakePayload.length);
        if (packetId == null || packetId.value() != 0) {
            return handshakePayload;
        }

        VarIntRead protocol = VarIntCodec.read(handshakePayload, packetId.next(), handshakePayload.length);
        if (protocol == null) {
            return handshakePayload;
        }

        VarIntRead hostLength = VarIntCodec.read(handshakePayload, protocol.next(), handshakePayload.length);
        if (hostLength == null || hostLength.value() < 0) {
            return handshakePayload;
        }

        int hostStart = hostLength.next();
        int hostEnd = hostStart + hostLength.value();
        int portEnd = hostEnd + 2;
        if (portEnd > handshakePayload.length) {
            return handshakePayload;
        }

        VarIntRead nextState = VarIntCodec.read(handshakePayload, portEnd, handshakePayload.length);
        if (nextState == null || nextState.value() != 2) {
            return handshakePayload;
        }

        String originalHost = new String(handshakePayload, hostStart, hostLength.value(), StandardCharsets.UTF_8);
        String forwardedHost = appendForwardedIpMarker(originalHost, sourceIp);
        byte[] hostBytes = forwardedHost.getBytes(StandardCharsets.UTF_8);
        if (hostBytes.length > 255) {
            LOGGER.warn("[zstdnet-server] skipped forwarded real IP for {} because forwarded handshake host is too long: {} bytes", sourceIp, hostBytes.length);
            return handshakePayload;
        }

        LOGGER.info("[zstdnet-server] appended forwarded real IP {} to login handshake host '{}'", sourceIp, originalHost);

        return ByteArrayOps.concat(
            ByteArrayOps.slice(handshakePayload, 0, protocol.next()),
            VarIntCodec.encode(hostBytes.length),
            hostBytes,
            ByteArrayOps.slice(handshakePayload, hostEnd, handshakePayload.length)
        );
    }

    private static String appendForwardedIpMarker(String originalHost, String sourceIp) {
        String marker = "zstdnet-real-ip=" + Base64.getUrlEncoder().withoutPadding().encodeToString(sourceIp.getBytes(StandardCharsets.UTF_8));
        int firstNull = originalHost.indexOf('\0');
        if (firstNull >= 0) {
            return originalHost + "\0" + marker;
        }

        int fmlMarker = originalHost.indexOf(" FML");
        if (fmlMarker >= 0) {
            String host = originalHost.substring(0, fmlMarker).trim();
            String version = originalHost.substring(fmlMarker + 1).trim();
            if (!host.isEmpty() && !version.isEmpty()) {
                return host + "\0" + version + "\0" + marker;
            }
        }

        return originalHost + "\0" + marker;
    }

    /**
     * 后端 -> 客户端：zstd 压缩后透传，并按配置执行 flush 与限速。
     */
    /**
     * Raw status traffic bypasses compression but still updates bandwidth stats as 100% ratio traffic.
     */
    private void streamRaw(InputStream in, OutputStream out, TrafficStats stats, boolean upstream) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) {
                out.write(buf, 0, n);
                addRawPassthroughStats(n, stats, upstream);
            }
        }
    }

    /**
     * Backend -> client: compress outbound traffic with zstd and apply flush/rate controls.
     */
    private void forwardCompress(
        OutputStream dst,
        Socket src,
        int level,
        Duration flushInterval,
        TrafficStats stats,
        TokenBucketLimiter perConnLimiter,
        TokenBucketLimiter globalLimiter
    ) throws IOException {
        OutputStream limitedDst = new RateLimitedOutputStream(dst, perConnLimiter, globalLimiter);
        try (ZstdOutputStream zstdOut = new ZstdOutputStream(new CountingOutputStream(limitedDst, stats::addZstdDown), level)) {
            zstdOut.setCloseFrameOnFlush(false);
            InputStream srcIn = src.getInputStream();
            byte[] buf = new byte[16 * 1024];
            final long flushIntervalNs = Math.max(0L, flushInterval.toNanos());
            long lastFlushNs = System.nanoTime();
            int originalTimeout = src.getSoTimeout();
            int activeTimeout = originalTimeout;
            boolean hasPending = false;
            int n;
            try {
                while (true) {
                    if (flushIntervalNs > 0L) {
                        int desiredTimeout = originalTimeout;
                        if (hasPending) {
                            long elapsedNs = System.nanoTime() - lastFlushNs;
                            long remainingNs = Math.max(1L, flushIntervalNs - elapsedNs);
                            long remainingMs = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs));
                            long boundedMs = Math.min((long) Integer.MAX_VALUE, remainingMs);
                            desiredTimeout = (int) boundedMs;
                            if (originalTimeout > 0) {
                                desiredTimeout = Math.min(desiredTimeout, originalTimeout);
                            }
                        }
                        if (desiredTimeout != activeTimeout) {
                            src.setSoTimeout(desiredTimeout);
                            activeTimeout = desiredTimeout;
                        }
                    }

                    try {
                        n = srcIn.read(buf);
                    } catch (SocketTimeoutException timeout) {
                        if (flushIntervalNs > 0L && hasPending && (System.nanoTime() - lastFlushNs) >= flushIntervalNs) {
                            zstdOut.flush();
                            hasPending = false;
                            lastFlushNs = System.nanoTime();
                        }
                        continue;
                    }

                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }

                    stats.addRawDown(n);
                    zstdOut.write(buf, 0, n);
                    hasPending = true;
                    if (flushIntervalNs == 0L || (System.nanoTime() - lastFlushNs) >= flushIntervalNs) {
                        zstdOut.flush();
                        hasPending = false;
                        lastFlushNs = System.nanoTime();
                    }
                }
            } finally {
                src.setSoTimeout(originalTimeout);
            }

            zstdOut.flush();
        }
    }

    /**
     * 解析 PROXY protocol v2（仅处理 TCP over IPv4/IPv6）。
     */
    private ProxyInfo parseProxyProtocolV2(PushbackInputStream in) throws IOException {
        byte[] first = new byte[PROXY_V2_SIGNATURE.length];
        int n = readSome(in, first);
        if (n < 0) {
            return ProxyInfo.invalid();
        }
        if (n < PROXY_V2_SIGNATURE.length) {
            in.unread(first, 0, n);
            return ProxyInfo.invalid();
        }
        if (!Arrays.equals(first, PROXY_V2_SIGNATURE)) {
            in.unread(first);
            return ProxyInfo.invalid();
        }

        byte[] fixed = PacketIo.readFully(in, 4);
        int verCmd = fixed[0] & 0xFF;
        int famProto = fixed[1] & 0xFF;
        int payloadLen = ((fixed[2] & 0xFF) << 8) | (fixed[3] & 0xFF);
        if (payloadLen > MAX_PROXY_V2_PAYLOAD_SIZE) {
            throw new IOException("PROXY protocol v2 payload too large: " + payloadLen);
        }
        byte[] payload = PacketIo.readFully(in, payloadLen);

        int version = (verCmd & 0xF0) >> 4;
        int command = verCmd & 0x0F;
        int family = (famProto & 0xF0) >> 4;
        int protocol = famProto & 0x0F;

        if (version != 0x2 || command != 0x1 || protocol != 0x1) {
            return ProxyInfo.invalid();
        }

        if (family == 0x1 && payload.length >= 12) {
            String srcIp = ipString(payload, 0, 4);
            String dstIp = ipString(payload, 4, 4);
            int srcPort = u16(payload, 8);
            int dstPort = u16(payload, 10);
            return new ProxyInfo(true, srcIp, srcPort, dstIp, dstPort);
        }
        if (family == 0x2 && payload.length >= 36) {
            String srcIp = ipString(payload, 0, 16);
            String dstIp = ipString(payload, 16, 16);
            int srcPort = u16(payload, 32);
            int dstPort = u16(payload, 34);
            return new ProxyInfo(true, srcIp, srcPort, dstIp, dstPort);
        }
        return ProxyInfo.invalid();
    }

    /**
     * 尽量读取指定缓冲区，允许短读。
     */
    private int readSome(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                return off == 0 ? -1 : off;
            }
            off += n;
            if (n == 0) {
                break;
            }
        }
        return off;
    }

    /**
     * 按长度精确读取，长度不足抛 EOF。
     */
    /**
     * Read the first raw Minecraft packet if it looks small enough to be a handshake; otherwise leave the stream untouched.
     */
    private byte[] tryReadPacketWire(PushbackInputStream in, int maxWaitMillis) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(200L, maxWaitMillis);
        byte[] prefix = new byte[5];
        int prefixLength = 0;

        while (System.currentTimeMillis() < deadline) {
            try {
                int next = in.read();
                if (next < 0) {
                    if (prefixLength == 0) {
                        return new byte[0];
                    }
                    throw new EOFException("unexpected eof during packet read");
                }

                if (prefixLength >= prefix.length) {
                    throw new IOException("packet length varint too large");
                }

                prefix[prefixLength++] = (byte) next;
                VarIntRead packetLength = VarIntCodec.read(prefix, 0, prefixLength);
                if (packetLength == null) {
                    continue;
                }

                if (packetLength.value() <= 0 || packetLength.value() > MAX_HANDSHAKE_PACKET_SIZE) {
                    in.unread(prefix, 0, prefixLength);
                    return null;
                }

                byte[] payload = PacketIo.readFully(in, packetLength.value());
                byte[] packet = new byte[prefixLength + payload.length];
                System.arraycopy(prefix, 0, packet, 0, prefixLength);
                System.arraycopy(payload, 0, packet, prefixLength, payload.length);
                return packet;
            } catch (SocketTimeoutException ignored) {
            }
        }

        if (prefixLength > 0) {
            in.unread(prefix, 0, prefixLength);
        }
        return new byte[0];
    }

    private boolean isStatusRequestPacket(byte[] payload) {
        return payload != null && payload.length == 1 && payload[0] == 0;
    }

    private boolean isLoginStartPacket(byte[] payload) {
        if (payload == null || payload.length < 3) {
            return false;
        }

        VarIntRead packetId = VarIntCodec.read(payload, 0, payload.length);
        if (packetId == null || packetId.value() != 0) {
            return false;
        }

        VarIntRead nameLength = VarIntCodec.read(payload, packetId.next(), payload.length);
        if (nameLength == null || nameLength.value() < 1 || nameLength.value() > 16) {
            return false;
        }

        int nameStart = nameLength.next();
        int nameEnd = nameStart + nameLength.value();
        if (nameEnd > payload.length) {
            return false;
        }

        for (int i = nameStart; i < nameEnd; i++) {
            int ch = payload[i] & 0xFF;
            boolean valid = (ch >= '0' && ch <= '9')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= 'a' && ch <= 'z')
                || ch == '_';
            if (!valid) {
                return false;
            }
        }

        return true;
    }

    private Integer extractHandshakeNextState(byte[] handshakePayload) {
        VarIntRead packetId = VarIntCodec.read(handshakePayload, 0, handshakePayload.length);
        if (packetId == null || packetId.value() != 0) {
            return null;
        }

        VarIntRead protocol = VarIntCodec.read(handshakePayload, packetId.next(), handshakePayload.length);
        if (protocol == null) {
            return null;
        }

        VarIntRead hostLength = VarIntCodec.read(handshakePayload, protocol.next(), handshakePayload.length);
        if (hostLength == null || hostLength.value() < 0) {
            return null;
        }

        int afterHost = hostLength.next() + hostLength.value();
        int afterPort = afterHost + 2;
        if (afterPort > handshakePayload.length) {
            return null;
        }

        VarIntRead nextState = VarIntCodec.read(handshakePayload, afterPort, handshakePayload.length);
        if (nextState == null || (nextState.value() != 1 && nextState.value() != 2)) {
            return null;
        }
        return nextState.value();
    }

    private void sendLoginDisconnect(Socket clientSocket, String message) {
        if (clientSocket == null || message == null || message.isBlank()) {
            return;
        }

        try {
            OutputStream out = clientSocket.getOutputStream();
            byte[] packet = buildLoginDisconnectPacket(message);
            out.write(packet);
            out.flush();
        } catch (IOException e) {
            LOGGER.debug("[server] failed to send raw-login disconnect packet: {}", e.toString());
        }
    }

    private byte[] buildLoginDisconnectPacket(String message) throws IOException {
        byte[] componentJson = buildTextComponentJson(message).getBytes(StandardCharsets.UTF_8);
        byte[] packetId = VarIntCodec.encode(0);
        byte[] componentLength = VarIntCodec.encode(componentJson.length);

        byte[] payload = new byte[packetId.length + componentLength.length + componentJson.length];
        int offset = 0;
        System.arraycopy(packetId, 0, payload, offset, packetId.length);
        offset += packetId.length;
        System.arraycopy(componentLength, 0, payload, offset, componentLength.length);
        offset += componentLength.length;
        System.arraycopy(componentJson, 0, payload, offset, componentJson.length);

        byte[] packetLength = VarIntCodec.encode(payload.length);
        byte[] packet = new byte[packetLength.length + payload.length];
        System.arraycopy(packetLength, 0, packet, 0, packetLength.length);
        System.arraycopy(payload, 0, packet, packetLength.length, payload.length);
        return packet;
    }

    private String buildTextComponentJson(String text) {
        return "{\"text\":\"" + escapeJson(text) + "\"}";
    }

    private String escapeJson(String text) {
        StringBuilder builder = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.toString();
    }

    private String ipString(byte[] data, int offset, int len) throws IOException {
        byte[] raw = Arrays.copyOfRange(data, offset, offset + len);
        return InetAddress.getByAddress(raw).getHostAddress();
    }

    private int u16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    /**
     * 定时输出流量与连接统计，同时触发防刷状态清理。
     */
    /**
     * Utility helpers for packet framing and passthrough traffic accounting.
     */
    private void addRawPassthroughStats(int bytes, TrafficStats stats, boolean upstream) {
        if (stats == null || bytes <= 0) {
            return;
        }
        if (upstream) {
            stats.addRawUp(bytes);
            stats.addZstdUp(bytes);
        } else {
            stats.addRawDown(bytes);
            stats.addZstdDown(bytes);
        }
    }

    private HudSnapshot buildHudSnapshot(long rawUpRate, long rawDownRate, long zstdUpRate, long zstdDownRate) {
        long raw = stats.rawBytes();
        long zstd = stats.zstdBytes();
        double ratio = raw <= 0 ? 0.0 : ((double) zstd * 100.0 / (double) raw);
        return new HudSnapshot(
            runtimeMode,
            cfg.listen.host,
            cfg.listen.port,
            raw,
            zstd,
            stats.rawUpBytes(),
            stats.rawDownBytes(),
            stats.zstdUpBytes(),
            stats.zstdDownBytes(),
            rawUpRate,
            rawDownRate,
            zstdUpRate,
            zstdDownRate,
            rawUpRate + rawDownRate,
            zstdUpRate + zstdDownRate,
            ratio,
            stats.activeConnections()
        );
    }

    private void startStatsPrinter() {
        AtomicLong prevHudRawUp = new AtomicLong(stats.rawUpBytes.get());
        AtomicLong prevHudRawDown = new AtomicLong(stats.rawDownBytes.get());
        AtomicLong prevHudZstdUp = new AtomicLong(stats.zstdUpBytes.get());
        AtomicLong prevHudZstdDown = new AtomicLong(stats.zstdDownBytes.get());

        statsTicker.scheduleAtFixedRate(() -> {
            FloodGuard currentGuard = guard;
            if (currentGuard != null) {
                currentGuard.sweepExpired();
            }

            long rawUp = stats.rawUpBytes.get();
            long rawDown = stats.rawDownBytes.get();
            long zstdUp = stats.zstdUpBytes.get();
            long zstdDown = stats.zstdDownBytes.get();
            latestHudSnapshot = buildHudSnapshot(
                rawUp - prevHudRawUp.getAndSet(rawUp),
                rawDown - prevHudRawDown.getAndSet(rawDown),
                zstdUp - prevHudZstdUp.getAndSet(zstdUp),
                zstdDown - prevHudZstdDown.getAndSet(zstdDown)
            );
        }, 1000L, 1000L, TimeUnit.MILLISECONDS);

        if (cfg.statsInterval.isZero() || cfg.statsInterval.isNegative()) {
            LOGGER.info("[zstdnet-server] periodic stats logging disabled.");
            return;
        }

        long periodMs = Math.max(250L, cfg.statsInterval.toMillis());
        AtomicLong prevRaw = new AtomicLong(stats.rawBytes.get());
        AtomicLong prevZstd = new AtomicLong(stats.zstdBytes.get());

        statsTicker.scheduleAtFixedRate(() -> {
            long raw = stats.rawBytes.get();
            long zstd = stats.zstdBytes.get();

            long dr = raw - prevRaw.getAndSet(raw);
            long dz = zstd - prevZstd.getAndSet(zstd);
            long rawPerSec = (long) (dr * (1000.0 / periodMs));
            long zstdPerSec = (long) (dz * (1000.0 / periodMs));
            double ratio = raw <= 0 ? 0.0 : ((double) zstd * 100.0 / (double) raw);

            String now = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            LOGGER.info("[{}] Raw: {} ({}) | Zstd: {} ({}) | Ratio: {}% | Conns: {}",
                now,
                formatSize(raw),
                formatRate(rawPerSec),
                formatSize(zstd),
                formatRate(zstdPerSec),
                String.format(Locale.ROOT, "%.2f", ratio),
                stats.activeConnections());
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int idx = 0;
        while (v >= 1024.0 && idx < units.length - 1) {
            v /= 1024.0;
            idx++;
        }
        return String.format(Locale.ROOT, "%.2f %s", v, units[idx]);
    }

    private String formatRate(long bytesPerSec) {
        if (bytesPerSec < 1024) {
            return bytesPerSec + "B/s";
        }
        String[] units = {"KB/s", "MB/s", "GB/s", "TB/s"};
        double v = bytesPerSec / 1024.0;
        int idx = 0;
        while (v >= 1024.0 && idx < units.length - 1) {
            v /= 1024.0;
            idx++;
        }
        return String.format(Locale.ROOT, "%.1f%s", v, units[idx]);
    }

    /**
     * 读取配置；若配置不存在则生成模板并提示用户编辑后重启。
     */
    private ProxyConfig loadOrCreateConfig(Path path, int mcServerPort, RuntimeMode mode) {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
                String body = buildDefaultConfig(mcServerPort, mode);
                Files.writeString(path, body, StandardCharsets.UTF_8);
                LOGGER.info("[zstdnet-server] generated config: {}", path);
                LOGGER.info("[zstdnet-server] 已生成默认配置：专用服会保留 server.properties 里的 server-port 作为公网入口，自动改用本地空闲端口作为后端，并拒绝原版直连登录。");
            } catch (IOException e) {
                LOGGER.error("[zstdnet-server] failed to create config {}: {}", path, e.toString());
            }
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.error("[zstdnet-server] failed reading config {}: {}", path, e.toString());
            return null;
        }
        loadedConfigLastModified = readLastModified(path);

        boolean enabled = Boolean.parseBoolean(props.getProperty("enabled", "false").trim());
        boolean autoTakeover = Boolean.parseBoolean(props.getProperty("auto_takeover", "false").trim());
        HostPort listen = HostPort.parse(props.getProperty("listen", "0.0.0.0:" + DEFAULT_LISTEN_PORT));
        HostPort target = HostPort.parse(props.getProperty(
            "target",
            "127.0.0.1:" + DEFAULT_MINECRAFT_PORT
        ));
        boolean voiceChatPassthrough = Boolean.parseBoolean(props.getProperty("voice_chat_passthrough", "true").trim());
        String voiceChatListen = props.getProperty("voice_chat_listen", "").trim();
        String voiceChatTarget = props.getProperty("voice_chat_target", "").trim();

        int level = clamp(parseInt(props.getProperty("level"), DEFAULT_ZSTD_LEVEL), 1, 22);
        int maxConn = parseInt(props.getProperty("max_conn_per_ip"), DEFAULT_MAX_CONN_PER_IP);
        int maxReq = parseInt(props.getProperty("max_req_per_window"), DEFAULT_MAX_REQ_PER_WINDOW);
        Duration window = parseDuration(props.getProperty("request_window"), Duration.ofSeconds(10));
        Duration ban = parseDuration(props.getProperty("ban_duration"), DEFAULT_BAN_DURATION);
        Duration statsInterval = parseDuration(props.getProperty("stats_interval"), Duration.ZERO);
        Duration flushInterval = parseDuration(props.getProperty("flush_interval"), Duration.ofMillis(2));
        Duration idleTimeout = parseDuration(props.getProperty("idle_timeout"), DEFAULT_IDLE_TIMEOUT);
        long maxRatePerConnBps = parseLong(props.getProperty("max_rate_per_conn_bps"), 0L);
        long maxRateGlobalBps = parseLong(props.getProperty("max_rate_global_bps"), 0L);
        int burstBytes = parseInt(props.getProperty("burst_bytes"), DEFAULT_BURST_BYTES);
        boolean trustProxyProtocol = Boolean.parseBoolean(props.getProperty("trust_proxy_protocol", "false").trim());
        Set<String> trustedProxyIps = parseTrustedProxyIps(props.getProperty("trusted_proxy_ips", DEFAULT_TRUSTED_PROXY_IPS));
        if (flushInterval.isNegative()) {
            flushInterval = Duration.ZERO;
        }
        if (idleTimeout.isNegative()) {
            idleTimeout = Duration.ZERO;
        }
        if (maxRatePerConnBps < 0) {
            maxRatePerConnBps = 0L;
        }
        if (maxRateGlobalBps < 0) {
            maxRateGlobalBps = 0L;
        }
        if (burstBytes <= 0) {
            burstBytes = DEFAULT_BURST_BYTES;
        }

        return new ProxyConfig(
            enabled,
            autoTakeover,
            listen,
            target,
            voiceChatPassthrough,
            voiceChatListen,
            voiceChatTarget,
            level,
            maxConn,
            maxReq,
            window,
            ban,
            statsInterval,
            flushInterval,
            idleTimeout,
            maxRatePerConnBps,
            maxRateGlobalBps,
            burstBytes,
            trustProxyProtocol,
            trustedProxyIps
        );
    }

    private Set<String> parseTrustedProxyIps(String raw) {
        LinkedHashSet<String> ips = new LinkedHashSet<>();
        String source = raw == null || raw.isBlank() ? DEFAULT_TRUSTED_PROXY_IPS : raw;
        for (String item : source.split(",")) {
            String value = item.trim();
            if (!value.isEmpty()) {
                ips.add(value);
            }
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1");
            ips.add("::1");
            ips.add("0:0:0:0:0:0:0:1");
        }
        return Set.copyOf(ips);
    }

    /**
     * 生成默认配置模板文本。
     */
    private String buildDefaultConfig(int mcServerPort, RuntimeMode mode) {
        if (mode == RuntimeMode.DEDICATED) {
            return buildDedicatedDefaultConfig();
        }

        return buildLanDefaultConfig(mcServerPort);
    }

    private String buildDedicatedDefaultConfig() {
        return """
            # ------------------------------------------------------------
            # zstdnet 内置服务端配置（自动生成）
            # ------------------------------------------------------------
            # 1) 专用服默认建议保持 auto_takeover=true。
            # 2) auto_takeover=true 时，公网入口跟随 server.properties 里的 server-port。
            # 3) 默认只透传原版状态查询，不透传原版登录。
            # 4) listen / target 仅在高级手动模式下需要额外填写。
            # 5) 地址不要写成 127.0.0.1.（末尾带点会解析失败）。

            # 是否启用内置 zstd 代理。
            enabled=true

            # 是否自动接管 server.properties 里的 server-port 作为公网入口。
            auto_takeover=true

            # 如需切回手动模式，可额外填写：
            # listen=0.0.0.0:25565
            # target=127.0.0.1:25566

            # Simple Voice Chat 的原样 UDP 转发。
            voice_chat_passthrough=true

            # 语音聊天的公网 UDP 入口；LAN 默认跟随当前游戏 UDP 端口。
            voice_chat_listen=0.0.0.0:${VOICE_PORT}

            # 语音聊天的后端 UDP 目标；LAN 默认指向当前游戏 UDP 端口。
            voice_chat_target=127.0.0.1:${VOICE_PORT}

            # zstd 压缩等级（1-22，通常建议 3-9）。
            level=${LEVEL}

            # 单个 IP 最大并发连接数（<=0 表示关闭限制）。
            max_conn_per_ip=${MAX_CONN}

            # 单个 IP 在 request_window 内最大请求次数（<=0 表示关闭限制）。
            max_req_per_window=${MAX_REQ}

            # 请求计数时间窗口。
            request_window=10s

            # 超限后的封禁时长。
            ban_duration=1m

            # 统计日志输出间隔。
            stats_interval=0s

            # zstd flush 间隔，0ms 表示每次写入都 flush。
            flush_interval=2ms

            # 后端读取空闲超时，0 表示关闭。
            idle_timeout=0

            # 单连接限速（字节/秒，0 表示关闭）。
            max_rate_per_conn_bps=0

            # 全局总限速（字节/秒，0 表示关闭）。
            max_rate_global_bps=0

            # 令牌桶突发容量（字节）。
            burst_bytes=${BURST_BYTES}

            # 如果玩家是通过 frp / 反代进服，并且你想让后端看到玩家真实 IP，就改成 true。
            # 普通直连、本机测试、局域网、公网直连都保持 false。
            # 改成 true 后，直接连 zstdnet 入口端口但不带 PROXY v2 头的连接会被拒绝。
            trust_proxy_protocol=false

            # 允许哪些机器转发“玩家真实 IP”给 zstdnet。
            # frpc 和服务端在同一台机器时不用改，保持 127.0.0.1 即可。
            # 如果 frpc 在另一台机器，就填那台机器连接到本服务器时使用的内网 IP。
            trusted_proxy_ips=${TRUSTED_PROXY_IPS}
            """
            .replace("${LEVEL}", String.valueOf(DEFAULT_ZSTD_LEVEL))
            .replace("${MAX_CONN}", String.valueOf(DEFAULT_MAX_CONN_PER_IP))
            .replace("${MAX_REQ}", String.valueOf(DEFAULT_MAX_REQ_PER_WINDOW))
            .replace("${BURST_BYTES}", String.valueOf(DEFAULT_BURST_BYTES))
            .replace("${TRUSTED_PROXY_IPS}", DEFAULT_TRUSTED_PROXY_IPS);
    }

    private String buildLanDefaultConfig(int mcServerPort) {
        return """
            # ------------------------------------------------------------
            # zstdnet 内置服务端配置（自动生成）
            # ------------------------------------------------------------
            # 1) 这个模板主要用于单机开房 / 局域网转发场景。
            # 2) 这里会直接写出当前 zstd 入口和后端游戏端口，方便房主查看和调整。
            # 3) 默认只透传原版状态查询，不透传原版登录。
            # 4) 地址不要写成 127.0.0.1.（末尾带点会解析失败）。

            # 是否启用内置 zstd 代理。
            enabled=true

            # 是否自动接管当前公开的游戏端口作为公网入口。
            auto_takeover=true

            # zstd 公网监听入口。
            # 单机 / 局域网场景下，这里通常就是当前对外分享的 zstd 端口。
            listen=0.0.0.0:${LISTEN_PORT}

            # 后端 Minecraft 地址。
            # 一般保持为本地游戏端口即可。
            target=127.0.0.1:${TARGET_PORT}

            # Simple Voice Chat 的原样 UDP 转发。
            voice_chat_passthrough=true

            # 语音聊天的公网 UDP 入口；留空时跟随当前 LAN 端口，填写后按配置值生效。
            voice_chat_listen=

            # 语音聊天的后端 UDP 目标；留空时指向本机当前 LAN 端口，填写后按配置值生效。
            voice_chat_target=

            # zstd 压缩等级（1-22，通常建议 3-9）。
            level=${LEVEL}

            # 单个 IP 最大并发连接数（<=0 表示关闭限制）。
            max_conn_per_ip=${MAX_CONN}

            # 单个 IP 在 request_window 内最大请求次数（<=0 表示关闭限制）。
            max_req_per_window=${MAX_REQ}

            # 请求计数时间窗口。
            request_window=10s

            # 超限后的封禁时长。
            ban_duration=1m

            # 统计日志输出间隔。
            stats_interval=0s

            # zstd flush 间隔，0ms 表示每次写入都 flush。
            flush_interval=2ms

            # 后端读取空闲超时，0 表示关闭。
            idle_timeout=0

            # 单连接限速（字节/秒，0 表示关闭）。
            max_rate_per_conn_bps=0

            # 全局总限速（字节/秒，0 表示关闭）。
            max_rate_global_bps=0

            # 令牌桶突发容量（字节）。
            burst_bytes=${BURST_BYTES}

            # 如果玩家是通过 frp / 反代进服，并且你想让后端看到玩家真实 IP，就改成 true。
            # 普通直连、本机测试、局域网、公网直连都保持 false。
            # 改成 true 后，直接连 zstdnet 入口端口但不带 PROXY v2 头的连接会被拒绝。
            trust_proxy_protocol=false

            # 允许哪些机器转发“玩家真实 IP”给 zstdnet。
            # frpc 和服务端在同一台机器时不用改，保持 127.0.0.1 即可。
            # 如果 frpc 在另一台机器，就填那台机器连接到本服务器时使用的内网 IP。
            trusted_proxy_ips=${TRUSTED_PROXY_IPS}
            """
            .replace("${LISTEN_PORT}", String.valueOf(mcServerPort))
            .replace("${TARGET_PORT}", String.valueOf(defaultAutoTargetPort(mcServerPort)))
            .replace("${VOICE_PORT}", String.valueOf(mcServerPort))
            .replace("${LEVEL}", String.valueOf(DEFAULT_ZSTD_LEVEL))
            .replace("${MAX_CONN}", String.valueOf(DEFAULT_MAX_CONN_PER_IP))
            .replace("${MAX_REQ}", String.valueOf(DEFAULT_MAX_REQ_PER_WINDOW))
            .replace("${BURST_BYTES}", String.valueOf(DEFAULT_BURST_BYTES))
            .replace("${TRUSTED_PROXY_IPS}", DEFAULT_TRUSTED_PROXY_IPS);
    }

    private int defaultAutoTargetPort(int publicPort) {
        int candidate = publicPort + 1;
        if (candidate <= 0 || candidate > 65535) {
            return DEFAULT_MINECRAFT_PORT + 1;
        }
        return candidate;
    }

    private VoiceChatPassthroughDecision resolveVoiceChatPassthrough(ProxyConfig config) {
        Path svcConfig = simpleVoiceChatConfigPath();
        Integer simpleVoiceChatPort = readSimpleVoiceChatPort(svcConfig);
        return resolveVoiceChatPassthrough(
            config.listen,
            config.target,
            config.voiceChatPassthrough,
            config.voiceChatListen,
            config.voiceChatTarget,
            simpleVoiceChatPort,
            svcConfig.toString()
        );
    }

    static VoiceChatPassthroughDecision resolveVoiceChatPassthrough(
        HostPort gameListen,
        HostPort gameTarget,
        boolean voiceChatPassthrough,
        String configuredListen,
        String configuredTarget,
        Integer simpleVoiceChatPort,
        String simpleVoiceChatSource
    ) {
        if (!voiceChatPassthrough) {
            return VoiceChatPassthroughDecision.disabled("voice chat UDP passthrough disabled by config");
        }
        if (gameListen == null || gameTarget == null) {
            return VoiceChatPassthroughDecision.disabled("game UDP route is unavailable");
        }

        boolean samePortMode = simpleVoiceChatPort != null && simpleVoiceChatPort == -1;
        HostPort target;
        try {
            target = parseOptionalHostPort(configuredTarget);
        } catch (IllegalArgumentException e) {
            return VoiceChatPassthroughDecision.disabled("invalid voice_chat_target: " + e.getMessage());
        }
        if (target == null) {
            if (simpleVoiceChatPort == null) {
                return VoiceChatPassthroughDecision.disabled(
                    "voice_chat_target is blank and Simple Voice Chat config was not found at " + simpleVoiceChatSource
                );
            }
            if (samePortMode) {
                target = gameTarget;
            } else if (simpleVoiceChatPort >= 1 && simpleVoiceChatPort <= 65535) {
                target = new HostPort("127.0.0.1", simpleVoiceChatPort);
            } else {
                return VoiceChatPassthroughDecision.disabled("invalid Simple Voice Chat port " + simpleVoiceChatPort);
            }
        }

        HostPort listen;
        try {
            listen = parseOptionalHostPort(configuredListen);
        } catch (IllegalArgumentException e) {
            return VoiceChatPassthroughDecision.disabled("invalid voice_chat_listen: " + e.getMessage());
        }
        if (listen == null) {
            if (simpleVoiceChatPort == null) {
                listen = new HostPort(gameListen.host(), target.port());
            } else if (samePortMode) {
                listen = gameListen;
            } else {
                return VoiceChatPassthroughDecision.disabled(
                    "Simple Voice Chat uses a separate port, so voice_chat_listen must be set explicitly"
                );
            }
        }

        if (listen.equals(gameListen) && target.equals(gameTarget)) {
            return VoiceChatPassthroughDecision.reuseGameRoute("voice chat UDP reuses the built-in game UDP route");
        }

        if (listen.port() == target.port() && isLocalHost(target.host())) {
            HostPort adjustedListen = chooseAlternateVoiceChatListen(listen, target);
            if (!adjustedListen.equals(listen)) {
                LOGGER.warn(
                    "[zstdnet-server] voice chat listen {} collides with local target {}; using {} instead.",
                    listen,
                    target,
                    adjustedListen
                );
                listen = adjustedListen;
            }
        }

        return VoiceChatPassthroughDecision.route(new UdpRoute("simple_voice_chat", listen, target));
    }

    private static HostPort chooseAlternateVoiceChatListen(HostPort listen, HostPort target) {
        int preferred = target.port() >= MAX_PORT ? MIN_PORT : target.port() + 1;
        int port = findFreeUdpPort(listen.host(), preferred, listen.port(), target.port());
        return port == listen.port() ? listen : new HostPort(listen.host(), port);
    }

    private static int findFreeUdpPort(String host, int preferredPort, int... reservedPorts) {
        String hostToProbe = host == null || host.isBlank() ? DEFAULT_LISTEN_HOST : host.trim();
        int start = Math.max(MIN_PORT, Math.min(MAX_PORT, preferredPort));

        for (int port = start; port <= MAX_PORT; port++) {
            if (!isReservedPort(port, reservedPorts) && isUdpBindable(hostToProbe, port)) {
                return port;
            }
        }
        for (int port = MIN_PORT; port < start; port++) {
            if (!isReservedPort(port, reservedPorts) && isUdpBindable(hostToProbe, port)) {
                return port;
            }
        }

        throw new IllegalStateException("no free UDP port available for voice chat passthrough");
    }

    private static boolean isReservedPort(int port, int... reservedPorts) {
        if (reservedPorts == null) {
            return false;
        }
        for (int reserved : reservedPorts) {
            if (reserved == port) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUdpBindable(String host, int port) {
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            InetSocketAddress address;
            if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
                address = new InetSocketAddress(port);
            } else {
                address = new InetSocketAddress(InetAddress.getByName(host), port);
            }
            socket.bind(address);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isLocalHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("localhost")
            || normalized.equals("127.0.0.1")
            || normalized.startsWith("127.")
            || normalized.equals("::1")
            || normalized.equals("0.0.0.0")
            || normalized.equals("::");
    }

    private Integer readSimpleVoiceChatPort(Path configPath) {
        if (configPath == null || !Files.exists(configPath)) {
            return null;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("[zstdnet-server] failed reading Simple Voice Chat config {}: {}", configPath, e.toString());
            return null;
        }
        String rawPort = props.getProperty("port");
        Integer parsedPort = parseSimpleVoiceChatPort(rawPort);
        if (parsedPort == null && rawPort != null && !rawPort.isBlank()) {
            LOGGER.warn("[zstdnet-server] invalid Simple Voice Chat port '{}' in {}", rawPort, configPath);
        }
        return parsedPort;
    }

    static Integer parseSimpleVoiceChatPort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static HostPort parseOptionalHostPort(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return HostPort.parse(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(raw.trim(), e);
        }
    }

    private static Path simpleVoiceChatConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("voicechat").resolve("voicechat-server.properties");
    }

    private int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private long parseLong(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private Duration parseDuration(String raw, Duration fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (text.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
            }
            if (text.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(text));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void applyReadTimeout(Socket socket, Duration timeout) {
        if (socket == null || timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        long timeoutMs = Math.max(1L, timeout.toMillis());
        int bounded = (int) Math.min((long) Integer.MAX_VALUE, timeoutMs);
        try {
            socket.setSoTimeout(bounded);
        } catch (Exception ignored) {
        }
    }

    private String sourceIp(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            InetAddress ip = inet.getAddress();
            return ip != null ? ip.getHostAddress() : inet.getHostString();
        }
        return String.valueOf(address);
    }

    private boolean isRealPipeErr(Exception err) {
        if (err == null || err instanceof EOFException) {
            return false;
        }
        String msg = err.toString().toLowerCase(Locale.ROOT);
        return !(msg.contains("broken pipe") || msg.contains("connection reset") || msg.contains("socket closed"));
    }

    private void closeWrite(Socket socket) {
        try {
            socket.shutdownOutput();
        } catch (Exception ignored) {
        }
    }

    private void closeSocket(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void closeQuietly(ServerSocket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private void shutdownQuietly(ExecutorService executor) {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
    }

    private long readLastModified(Path path) {
        try {
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : Long.MIN_VALUE;
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * PROXY protocol 解析结果模块。
     */
    private record ProxyInfo(boolean valid, String sourceIp, int sourcePort, String targetIp, int targetPort) {
        static ProxyInfo invalid() {
            return new ProxyInfo(false, null, 0, null, 0);
        }
    }

    private record BindResult(ServerSocket listener, ProxyConfig config) {
    }

    /**
     * 服务端配置快照模块。
     */
    private enum ClientMode {
        ZSTD,
        RAW_STATUS,
        RAW_LOGIN
    }

    private enum RuntimeMode {
        DEDICATED,
        LAN
    }

    record HudSnapshot(
        RuntimeMode mode,
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
        String modeName() {
            return mode.name();
        }
    }

    private record DetectedClientMode(ClientMode mode, byte[] initialWireData) {
        private static DetectedClientMode zstd() {
            return new DetectedClientMode(ClientMode.ZSTD, null);
        }
    }

    private record ProxyConfig(
        boolean enabled,
        boolean autoTakeover,
        HostPort listen,
        HostPort target,
        boolean voiceChatPassthrough,
        String voiceChatListen,
        String voiceChatTarget,
        int level,
        int maxConnPerIp,
        int maxReqPerWindow,
        Duration window,
        Duration banDuration,
        Duration statsInterval,
        Duration flushInterval,
        Duration idleTimeout,
        long maxRatePerConnBps,
        long maxRateGlobalBps,
        int burstBytes,
        boolean trustProxyProtocol,
        Set<String> trustedProxyIps
    ) {
        private ProxyConfig withTarget(HostPort newTarget) {
            return new ProxyConfig(
                enabled,
                autoTakeover,
                listen,
                newTarget,
                voiceChatPassthrough,
                voiceChatListen,
                voiceChatTarget,
                level,
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes,
                trustProxyProtocol,
                trustedProxyIps
            );
        }

        private ProxyConfig withEndpoints(HostPort newListen, HostPort newTarget) {
            return new ProxyConfig(
                enabled,
                autoTakeover,
                newListen,
                newTarget,
                voiceChatPassthrough,
                voiceChatListen,
                voiceChatTarget,
                level,
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes,
                trustProxyProtocol,
                trustedProxyIps
            );
        }

        private ProxyConfig withLanVoiceDefaults(int lanPort) {
            String effectiveVoiceListen = voiceChatListen;
            String effectiveVoiceTarget = voiceChatTarget;
            if (isDefaultVoiceChatListen(voiceChatListen)) {
                effectiveVoiceListen = DEFAULT_LISTEN_HOST + ":" + lanPort;
            }
            if (isDefaultVoiceChatTarget(voiceChatTarget)) {
                effectiveVoiceTarget = "127.0.0.1:" + lanPort;
            }
            return new ProxyConfig(
                enabled,
                autoTakeover,
                listen,
                target,
                voiceChatPassthrough,
                effectiveVoiceListen,
                effectiveVoiceTarget,
                level,
                maxConnPerIp,
                maxReqPerWindow,
                window,
                banDuration,
                statsInterval,
                flushInterval,
                idleTimeout,
                maxRatePerConnBps,
                maxRateGlobalBps,
                burstBytes,
                trustProxyProtocol,
                trustedProxyIps
            );
        }

        private static boolean isDefaultVoiceChatListen(String raw) {
            return raw == null || raw.isBlank();
        }

        private static boolean isDefaultVoiceChatTarget(String raw) {
            return raw == null || raw.isBlank();
        }

    }

    /**
     * host:port 解析模块（支持 IPv6/默认端口/去尾点）。
     */
    static record UdpRoute(String label, HostPort listen, HostPort target) {
    }

    static record VoiceChatPassthroughDecision(UdpRoute route, boolean reuseGameRoute, String reason) {
        static VoiceChatPassthroughDecision disabled(String reason) {
            return new VoiceChatPassthroughDecision(null, false, reason);
        }

        static VoiceChatPassthroughDecision reuseGameRoute(String reason) {
            return new VoiceChatPassthroughDecision(null, true, reason);
        }

        static VoiceChatPassthroughDecision route(UdpRoute route) {
            return new VoiceChatPassthroughDecision(route, false, null);
        }
    }

    static record HostPort(String host, int port) {
        static HostPort parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("empty host:port");
            }
            String value = raw.trim();

            if (value.startsWith("[") && value.contains("]")) {
                int end = value.indexOf(']');
                String host = value.substring(1, end);
                int port = DEFAULT_MINECRAFT_PORT;
                if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                    port = Integer.parseInt(value.substring(end + 2).trim());
                }
                return new HostPort(normalizeHost(host), port);
            }

            int lastColon = value.lastIndexOf(':');
            int firstColon = value.indexOf(':');
            if (lastColon > 0 && firstColon == lastColon) {
                String host = value.substring(0, lastColon).trim();
                int port = Integer.parseInt(value.substring(lastColon + 1).trim());
                return new HostPort(normalizeHost(host), port);
            }
            return new HostPort(normalizeHost(value), DEFAULT_MINECRAFT_PORT);
        }

        InetSocketAddress toAddress() {
            return new InetSocketAddress(host, port);
        }

        private static String normalizeHost(String host) {
            String h = host.trim();
            if (h.endsWith(".") && h.length() > 1) {
                h = h.substring(0, h.length() - 1);
            }
            return h;
        }
    }

    /**
     * 防刷模块：
     * - 限制每 IP 并发连接数
     * - 限制窗口内请求次数并封禁
     */
    private static final class FloodGuard {
        private final Map<String, GuardEntry> state = new ConcurrentHashMap<>();
        private final ProxyConfig cfg;

        private FloodGuard(ProxyConfig cfg) {
            this.cfg = cfg;
        }

        private synchronized boolean begin(String ip) {
            long now = System.currentTimeMillis();
            GuardEntry entry = state.computeIfAbsent(ip, k -> new GuardEntry());
            pruneRequests(entry, now);

            if (entry.bannedUntilMs > now) {
                return false;
            }

            if (cfg.maxReqPerWindow > 0 && !cfg.window.isZero() && !cfg.window.isNegative()) {
                entry.requestsMs.addLast(now);
                if (entry.requestsMs.size() > cfg.maxReqPerWindow) {
                    entry.bannedUntilMs = now + cfg.banDuration.toMillis();
                    return false;
                }
            }

            if (cfg.maxConnPerIp > 0 && entry.activeConn >= cfg.maxConnPerIp) {
                return false;
            }

            entry.activeConn++;
            return true;
        }

        private synchronized void end(String ip) {
            GuardEntry entry = state.get(ip);
            if (entry == null) {
                return;
            }
            if (entry.activeConn > 0) {
                entry.activeConn--;
            }
            long now = System.currentTimeMillis();
            pruneRequests(entry, now);
            if (isRemovable(entry, now)) {
                state.remove(ip);
            }
        }

        private synchronized void sweepExpired() {
            long now = System.currentTimeMillis();
            state.entrySet().removeIf(e -> {
                GuardEntry entry = e.getValue();
                pruneRequests(entry, now);
                return isRemovable(entry, now);
            });
        }

        private void pruneRequests(GuardEntry entry, long now) {
            if (cfg.window.isZero() || cfg.window.isNegative()) {
                entry.requestsMs.clear();
                return;
            }
            long cutoff = now - cfg.window.toMillis();
            while (!entry.requestsMs.isEmpty() && entry.requestsMs.peekFirst() < cutoff) {
                entry.requestsMs.removeFirst();
            }
        }

        private boolean isRemovable(GuardEntry entry, long now) {
            return entry.activeConn == 0 && entry.requestsMs.isEmpty() && entry.bannedUntilMs <= now;
        }

        private static final class GuardEntry {
            private int activeConn;
            private long bannedUntilMs;
            private final Deque<Long> requestsMs = new ArrayDeque<>();
        }
    }

    /**
     * 带限速控制的输出流模块。
     */
    private static final class RateLimitedOutputStream extends OutputStream {
        private static final int CHUNK_SIZE = 16 * 1024;

        private final OutputStream delegate;
        private final TokenBucketLimiter perConnLimiter;
        private final TokenBucketLimiter globalLimiter;

        private RateLimitedOutputStream(
            OutputStream delegate,
            TokenBucketLimiter perConnLimiter,
            TokenBucketLimiter globalLimiter
        ) {
            this.delegate = Objects.requireNonNull(delegate);
            this.perConnLimiter = perConnLimiter;
            this.globalLimiter = globalLimiter;
        }

        @Override
        public void write(int b) throws IOException {
            throttle(1);
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int written = 0;
            while (written < len) {
                int chunk = Math.min(CHUNK_SIZE, len - written);
                throttle(chunk);
                delegate.write(b, off + written, chunk);
                written += chunk;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void throttle(int n) {
            if (perConnLimiter != null) {
                perConnLimiter.waitBytes(n);
            }
            if (globalLimiter != null) {
                globalLimiter.waitBytes(n);
            }
        }
    }

    private void startUdpForwarders(ProxyConfig config) {
        List<UdpRoute> routes = buildUdpRoutes(config);
        List<UdpForwarder> started = new ArrayList<>();
        for (UdpRoute route : routes) {
            try {
                UdpForwarder forwarder = new UdpForwarder(route);
                forwarder.start();
                started.add(forwarder);
                LOGGER.info("[zstdnet-server] UDP route armed [{}]: {} -> {}", route.label(), route.listen(), route.target());
            } catch (Exception e) {
                LOGGER.warn("[zstdnet-server] UDP route skipped [{}] {} -> {}: {}", route.label(), route.listen(), route.target(), e.toString());
            }
        }
        this.udpForwarders = started;
    }

    private List<UdpRoute> buildUdpRoutes(ProxyConfig config) {
        List<UdpRoute> routes = new ArrayList<>();
        routes.add(new UdpRoute("game", config.listen, config.target));

        VoiceChatPassthroughDecision voiceChat = resolveVoiceChatPassthrough(config);
        if (voiceChat.reuseGameRoute()) {
            LOGGER.info("[zstdnet-server] voice chat UDP passthrough reuses the built-in game UDP route.");
            return routes;
        }
        if (voiceChat.route() != null) {
            routes.add(voiceChat.route());
            return routes;
        }

        if (config.voiceChatPassthrough) {
            LOGGER.warn("[zstdnet-server] voice chat UDP passthrough not armed: {}", voiceChat.reason());
        } else {
            LOGGER.info("[zstdnet-server] voice chat UDP passthrough disabled.");
        }
        return routes;
    }

    private void stopUdpForwarders() {
        List<UdpForwarder> forwarders = this.udpForwarders;
        this.udpForwarders = List.of();
        for (UdpForwarder forwarder : forwarders) {
            try {
                forwarder.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private static final class UdpForwarder {
        private static final int UDP_BUF_SIZE = 65535;
        private static final long SESSION_TIMEOUT_MS = 60_000L;

        private final UdpRoute route;
        private volatile boolean running;
        private DatagramSocket serverSocket;
        private Thread forwardThread;
        private final Map<SocketAddress, UdpSession> sessions = new ConcurrentHashMap<>();

        UdpForwarder(UdpRoute route) {
            this.route = route;
        }

        void start() throws IOException {
            serverSocket = new DatagramSocket(null);
            serverSocket.setReuseAddress(true);
            serverSocket.bind(route.listen().toAddress());
            serverSocket.setSoTimeout(1000);
            running = true;

            forwardThread = new Thread(this::forwardLoop, "zstdsrv-udp-fwd-" + route.label());
            forwardThread.setDaemon(true);
            forwardThread.start();
        }

        void stop() {
            running = false;
            if (serverSocket != null) {
                serverSocket.close();
            }
            for (UdpSession session : sessions.values()) {
                session.close();
            }
            sessions.clear();
            if (forwardThread != null) {
                try {
                    forwardThread.join(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void forwardLoop() {
            byte[] buf = new byte[UDP_BUF_SIZE];
            InetSocketAddress targetAddr = route.target().toAddress();
            long lastSweep = System.currentTimeMillis();

            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        serverSocket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        sweepIfNeeded(lastSweep);
                        lastSweep = System.currentTimeMillis();
                        continue;
                    }

                    SocketAddress clientAddr = packet.getSocketAddress();
                    UdpSession session = sessions.computeIfAbsent(clientAddr, k -> createSession(k, targetAddr));
                    if (session == null) {
                        continue;
                    }
                    session.lastActivity = System.currentTimeMillis();

                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                    DatagramPacket forward = new DatagramPacket(data, data.length, targetAddr);
                    session.socket.send(forward);

                    long now = System.currentTimeMillis();
                    if (now - lastSweep > 10_000L) {
                        sweepIfNeeded(lastSweep);
                        lastSweep = now;
                    }
                } catch (IOException e) {
                    if (running) {
                        LOGGER.debug("[zstdnet-server] UDP forward error [{}]: {}", route.label(), e.toString());
                    }
                }
            }
        }

        private UdpSession createSession(SocketAddress clientAddr, InetSocketAddress targetAddr) {
            try {
                DatagramSocket clientSocket = new DatagramSocket();
                clientSocket.setSoTimeout(1000);
                UdpSession session = new UdpSession(clientSocket, clientAddr);

                Thread returnThread = new Thread(() -> returnLoop(session), "zstdsrv-udp-ret-" + clientAddr);
                returnThread.setDaemon(true);
                returnThread.start();
                session.returnThread = returnThread;
                return session;
            } catch (IOException e) {
                LOGGER.debug("[zstdnet-server] UDP session create failed [{}] for {}: {}", route.label(), clientAddr, e.toString());
                return null;
            }
        }

        private void returnLoop(UdpSession session) {
            byte[] buf = new byte[UDP_BUF_SIZE];
            while (running && !session.socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        session.socket.receive(packet);
                    } catch (SocketTimeoutException ignored) {
                        if (System.currentTimeMillis() - session.lastActivity > SESSION_TIMEOUT_MS) {
                            break;
                        }
                        continue;
                    }
                    session.lastActivity = System.currentTimeMillis();

                    byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                    DatagramPacket returnPacket = new DatagramPacket(data, data.length, (InetSocketAddress) session.clientAddr);
                    serverSocket.send(returnPacket);
                } catch (IOException e) {
                    if (running && !session.socket.isClosed()) {
                        LOGGER.debug("[zstdnet-server] UDP return error [{}] for {}: {}", route.label(), session.clientAddr, e.toString());
                    }
                    break;
                }
            }
            sessions.remove(session.clientAddr);
            session.close();
        }

        private void sweepIfNeeded(long lastSweep) {
            long now = System.currentTimeMillis();
            if (now - lastSweep < 10_000L) {
                return;
            }
            sessions.entrySet().removeIf(entry -> {
                UdpSession s = entry.getValue();
                if (now - s.lastActivity > SESSION_TIMEOUT_MS) {
                    s.close();
                    return true;
                }
                return false;
            });
        }

        private static final class UdpSession {
            final DatagramSocket socket;
            final SocketAddress clientAddr;
            volatile long lastActivity;
            volatile Thread returnThread;

            UdpSession(DatagramSocket socket, SocketAddress clientAddr) {
                this.socket = socket;
                this.clientAddr = clientAddr;
                this.lastActivity = System.currentTimeMillis();
            }

            void close() {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 线程命名工厂模块，便于日志与诊断定位线程用途。
     */
    private static final class NamedFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger index = new AtomicInteger(1);

        private NamedFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + index.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
