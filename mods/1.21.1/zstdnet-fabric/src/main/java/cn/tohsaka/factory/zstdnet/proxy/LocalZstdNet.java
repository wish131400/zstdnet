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

package cn.tohsaka.factory.zstdnet.proxy;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LocalZstdNet {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicInteger WORKER_SEQ = new AtomicInteger(1);
    private static final AtomicInteger ACCEPT_SEQ = new AtomicInteger(1);
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "zstdnet-worker-" + WORKER_SEQ.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    public enum Mode {
        AUTO,
        RAW,
        ZSTD
    }

    private LocalZstdNet() {
    }

    public static ProxyHandle start(String remoteHost, int remotePort, int level, Mode requestedMode) throws IOException {
        return start(remoteHost, remotePort, remoteHost, remotePort, remoteHost, remotePort, level, requestedMode);
    }

    public static ProxyHandle start(
        String remoteHost,
        int remotePort,
        String presentedHost,
        int presentedPort,
        int level,
        Mode requestedMode
    ) throws IOException {
        return start(remoteHost, remotePort, remoteHost, remotePort, presentedHost, presentedPort, level, requestedMode);
    }

    public static ProxyHandle start(
        String remoteHost,
        int remotePort,
        String statusHost,
        int statusPort,
        String presentedHost,
        int presentedPort,
        int level,
        Mode requestedMode
    ) throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));

        Mode resolvedMode = resolveMode(remoteHost, remotePort, requestedMode);
        AtomicBoolean running = new AtomicBoolean(true);
        ProxyStats stats = new ProxyStats();
        Thread acceptThread = new Thread(
            () -> acceptLoop(listener, running, remoteHost, remotePort, statusHost, statusPort, presentedHost, presentedPort, level, resolvedMode, stats),
            "zstdnet-accept-" + ACCEPT_SEQ.getAndIncrement() + "-" + remoteHost + ":" + remotePort
        );
        acceptThread.setDaemon(true);
        acceptThread.start();

        return new ProxyHandle(listener, running, acceptThread, resolvedMode, remoteHost, remotePort, stats);
    }

    private static Mode resolveMode(String remoteHost, int remotePort, Mode requestedMode) {
        if (requestedMode == null || requestedMode == Mode.AUTO) {
            Mode picked = probeRawStatus(remoteHost, remotePort) ? Mode.RAW : Mode.ZSTD;
            LOGGER.info("zstdnet: auto mode {}:{} -> {}", remoteHost, remotePort, picked);
            return picked;
        }
        return requestedMode;
    }

    private static boolean probeRawStatus(String remoteHost, int remotePort) {
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(remoteHost, remotePort), 1500);
            probe.setSoTimeout(1500);

            byte[] hostBytes = remoteHost.getBytes(StandardCharsets.UTF_8);
            byte[] handshakePayload = concat(
                encodeVarInt(0),
                encodeVarInt(763),
                encodeVarInt(hostBytes.length),
                hostBytes,
                new byte[]{(byte) (remotePort >>> 8), (byte) remotePort},
                encodeVarInt(1)
            );

            writePacket(probe.getOutputStream(), handshakePayload);
            writePacket(probe.getOutputStream(), encodeVarInt(0));

            byte[] firstPacket = readPacket(probe.getInputStream());
            if (firstPacket.length == 0) {
                return false;
            }

            VarIntRead packetId = readVarInt(firstPacket, 0);
            if (packetId == null || packetId.value != 0) {
                return false;
            }

            VarIntRead jsonLength = readVarInt(firstPacket, packetId.next);
            if (jsonLength == null || jsonLength.value < 0) {
                return false;
            }

            return jsonLength.next + jsonLength.value <= firstPacket.length;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void acceptLoop(
        ServerSocket listener,
        AtomicBoolean running,
        String remoteHost,
        int remotePort,
        String statusHost,
        int statusPort,
        String presentedHost,
        int presentedPort,
        int level,
        Mode mode,
        ProxyStats stats
    ) {
        while (running.get()) {
            try {
                Socket localClient = listener.accept();
                WORKERS.execute(() -> handleConnection(
                    localClient,
                    remoteHost,
                    remotePort,
                    statusHost,
                    statusPort,
                    presentedHost,
                    presentedPort,
                    level,
                    mode,
                    stats
                ));
            } catch (SocketException e) {
                if (running.get()) {
                    LOGGER.warn("zstdnet: accept failed: {}", e.toString());
                }
                return;
            } catch (Exception e) {
                LOGGER.warn("zstdnet: accept failed: {}", e.toString());
            }
        }
    }

    private static void handleConnection(
        Socket localClient,
        String remoteHost,
        int remotePort,
        String statusHost,
        int statusPort,
        String presentedHost,
        int presentedPort,
        int level,
        Mode mode,
        ProxyStats stats
    ) {
        try {
            localClient.setTcpNoDelay(true);
            localClient.setSoTimeout(5000);
            byte[] handshake = readPacketWithRetry(localClient.getInputStream(), 5000);
            if (handshake.length == 0) {
                LOGGER.warn("zstdnet: handshake timeout from {} for {}:{}", localClient.getRemoteSocketAddress(), remoteHost, remotePort);
                closeQuietly(localClient);
                return;
            }

            localClient.setSoTimeout(0);
            byte[] rewrittenHandshake = rewriteHandshakeDestination(handshake, presentedHost, presentedPort);
            Integer nextState = extractHandshakeNextState(handshake);
            boolean isStatus = nextState != null && nextState == 1;

            if (isStatus) {
                handleRawConnection(localClient, statusHost, statusPort, rewrittenHandshake, stats);
                return;
            }

            if (mode == Mode.RAW) {
                handleRawConnection(localClient, remoteHost, remotePort, rewrittenHandshake, stats);
            } else {
                handleZstdConnection(localClient, remoteHost, remotePort, level, rewrittenHandshake, stats);
            }
        } catch (Exception e) {
            LOGGER.debug("zstdnet: proxy pipe closed: {}", e.toString());
            closeQuietly(localClient);
        }
    }

    private static void handleRawConnection(
        Socket localClient,
        String remoteHost,
        int remotePort,
        byte[] firstPacket,
        ProxyStats stats
    ) throws Exception {
        try (Socket client = localClient; Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(0);

            OutputStream upstreamOut = new CountingOutputStream(upstream.getOutputStream(), stats::addClientToServerRawPassthrough);
            writePacket(upstreamOut, firstPacket);
            upstreamOut.flush();

            Future<?> upstreamWriter = WORKERS.submit(() -> {
                try {
                    streamCopy(client.getInputStream(), upstreamOut);
                } catch (Exception ignored) {
                } finally {
                    try {
                        upstream.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            Future<?> downstreamWriter = WORKERS.submit(() -> {
                try {
                    streamCopy(
                        new CountingInputStream(upstream.getInputStream(), stats::addServerToClientRawPassthrough),
                        client.getOutputStream()
                    );
                } catch (Exception ignored) {
                } finally {
                    try {
                        client.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            upstreamWriter.get();
            downstreamWriter.get();
        }
    }

    private static void handleZstdConnection(
        Socket localClient,
        String remoteHost,
        int remotePort,
        int level,
        byte[] firstPacket,
        ProxyStats stats
    ) throws Exception {
        try (Socket client = localClient; Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(0);

            Future<?> upstreamWriter = WORKERS.submit(() -> {
                try (ZstdOutputStream zstdOut = new ZstdOutputStream(
                    new CountingOutputStream(upstream.getOutputStream(), stats::addClientToServerZstd),
                    level
                )) {
                    zstdOut.setCloseFrameOnFlush(false);
                    OutputStream countedRawOut = new CountingOutputStream(zstdOut, stats::addClientToServerRaw);
                    writePacket(countedRawOut, firstPacket);
                    countedRawOut.flush();
                    streamCompress(client.getInputStream(), countedRawOut);
                } catch (Exception ignored) {
                } finally {
                    try {
                        upstream.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            Future<?> downstreamWriter = WORKERS.submit(() -> {
                try (ZstdInputStream zstdIn = new ZstdInputStream(
                    new CountingInputStream(upstream.getInputStream(), stats::addServerToClientZstd)
                )) {
                    streamCopy(zstdIn, new CountingOutputStream(client.getOutputStream(), stats::addServerToClientRaw));
                } catch (Exception ignored) {
                } finally {
                    try {
                        client.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            upstreamWriter.get();
            downstreamWriter.get();
        }
    }

    private static byte[] readPacketWithRetry(InputStream in, int maxWaitMillis) throws IOException {
        long deadline = System.currentTimeMillis() + Math.max(200L, maxWaitMillis);
        byte[] prefix = new byte[5];
        int prefixLength = 0;
        Integer payloadLength = null;
        int payloadStart = -1;
        byte[] payload = null;
        int payloadRead = 0;

        while (System.currentTimeMillis() < deadline) {
            try {
                int next = in.read();
                if (next < 0) {
                    if (prefixLength == 0 && payloadRead == 0) {
                        return new byte[0];
                    }
                    throw new EOFException("unexpected eof during packet read");
                }

                if (payloadLength == null) {
                    if (prefixLength >= prefix.length) {
                        throw new IOException("packet length varint too large");
                    }
                    prefix[prefixLength++] = (byte) next;
                    VarIntRead packetLength = readVarInt(prefix, 0, prefixLength);
                    if (packetLength != null) {
                        payloadLength = packetLength.value;
                        payloadStart = packetLength.next;
                        if (payloadLength <= 0) {
                            return new byte[0];
                        }
                        payload = new byte[payloadLength];
                        int extra = prefixLength - payloadStart;
                        if (extra > 0) {
                            System.arraycopy(prefix, payloadStart, payload, 0, extra);
                            payloadRead = extra;
                        }
                    }
                } else {
                    payload[payloadRead++] = (byte) next;
                }

                if (payloadLength != null && payloadRead >= payloadLength) {
                    return payload;
                }
            } catch (SocketTimeoutException ignored) {
            } catch (SocketException e) {
                String message = e.getMessage();
                if (message != null && message.toLowerCase().contains("timed out")) {
                    continue;
                }
                throw e;
            }
        }

        return new byte[0];
    }

    private static Integer extractHandshakeNextState(byte[] handshakePayload) {
        VarIntRead packetId = readVarInt(handshakePayload, 0);
        if (packetId == null || packetId.value != 0) {
            return null;
        }

        VarIntRead protocol = readVarInt(handshakePayload, packetId.next);
        if (protocol == null) {
            return null;
        }

        VarIntRead hostLength = readVarInt(handshakePayload, protocol.next);
        if (hostLength == null || hostLength.value < 0) {
            return null;
        }

        int afterHost = hostLength.next + hostLength.value;
        int afterPort = afterHost + 2;
        if (afterPort > handshakePayload.length) {
            return null;
        }

        VarIntRead nextState = readVarInt(handshakePayload, afterPort);
        return nextState == null ? null : nextState.value;
    }

    private static byte[] rewriteHandshakeDestination(byte[] handshakePayload, String host, int port) {
        if (handshakePayload == null || handshakePayload.length == 0 || host == null || host.isBlank()) {
            return handshakePayload;
        }

        VarIntRead packetId = readVarInt(handshakePayload, 0);
        if (packetId == null || packetId.value != 0) {
            return handshakePayload;
        }

        VarIntRead protocol = readVarInt(handshakePayload, packetId.next);
        if (protocol == null) {
            return handshakePayload;
        }

        VarIntRead hostLength = readVarInt(handshakePayload, protocol.next);
        if (hostLength == null || hostLength.value < 0) {
            return handshakePayload;
        }

        int hostStart = hostLength.next;
        int hostEnd = hostStart + hostLength.value;
        int portStart = hostEnd;
        int portEnd = portStart + 2;
        if (portEnd > handshakePayload.length) {
            return handshakePayload;
        }

        String originalHost = new String(handshakePayload, hostStart, hostLength.value, StandardCharsets.UTF_8);
        String hostSuffix = extractHandshakeHostSuffix(originalHost);
        byte[] hostBytes = (host + hostSuffix).getBytes(StandardCharsets.UTF_8);
        return concat(
            slice(handshakePayload, 0, protocol.next),
            encodeVarInt(hostBytes.length),
            hostBytes,
            new byte[]{(byte) (port >>> 8), (byte) port},
            slice(handshakePayload, portEnd, handshakePayload.length)
        );
    }

    private static String extractHandshakeHostSuffix(String originalHost) {
        if (originalHost == null || originalHost.isEmpty()) {
            return "";
        }
        int markerIndex = originalHost.indexOf('\0');
        if (markerIndex < 0) {
            return "";
        }
        return originalHost.substring(markerIndex);
    }

    private static void streamCompress(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }
    }

    private static void streamCopy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        }
    }

    private static byte[] readPacket(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (length <= 0) {
            return new byte[0];
        }
        return readFully(in, length);
    }

    private static void writePacket(OutputStream out, byte[] payload) throws IOException {
        out.write(encodeVarInt(payload.length));
        if (payload.length > 0) {
            out.write(payload);
        }
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int next = in.read();
            if (next < 0) {
                if (position == 0) {
                    return -1;
                }
                throw new EOFException("eof during varint");
            }

            value |= (next & 0x7F) << position;
            if ((next & 0x80) == 0) {
                return value;
            }

            position += 7;
            if (position > 28) {
                throw new IOException("varint too big");
            }
        }
    }

    private static VarIntRead readVarInt(byte[] data, int start) {
        return readVarInt(data, start, data.length);
    }

    private static VarIntRead readVarInt(byte[] data, int start, int endExclusive) {
        int value = 0;
        int position = 0;
        int index = start;

        while (index < endExclusive) {
            int next = data[index++] & 0xFF;
            value |= (next & 0x7F) << position;
            if ((next & 0x80) == 0) {
                return new VarIntRead(value, index);
            }

            position += 7;
            if (position > 28) {
                return null;
            }
        }

        return null;
    }

    private static byte[] encodeVarInt(int value) {
        int working = value;
        byte[] buffer = new byte[5];
        int index = 0;

        do {
            int next = working & 0x7F;
            working >>>= 7;
            if (working != 0) {
                next |= 0x80;
            }
            buffer[index++] = (byte) next;
        } while (working != 0);

        byte[] out = new byte[index];
        System.arraycopy(buffer, 0, out, 0, index);
        return out;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) {
                throw new EOFException("unexpected eof");
            }
            offset += read;
        }
        return data;
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] array : arrays) {
            if (array != null) {
                total += array.length;
            }
        }

        byte[] merged = new byte[total];
        int offset = 0;
        for (byte[] array : arrays) {
            if (array == null || array.length == 0) {
                continue;
            }
            System.arraycopy(array, 0, merged, offset, array.length);
            offset += array.length;
        }
        return merged;
    }

    private static byte[] slice(byte[] source, int startInclusive, int endExclusive) {
        int start = Math.max(0, startInclusive);
        int end = Math.max(start, Math.min(source.length, endExclusive));
        byte[] out = new byte[end - start];
        System.arraycopy(source, start, out, 0, out.length);
        return out;
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (Exception ignored) {
        }
    }

    private record VarIntRead(int value, int next) {
    }

    public record HostPort(String host, int port) {
        public static HostPort parse(String raw) {
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException("empty addr");
            }

            String value = raw.trim();
            if (value.startsWith("[") && value.contains("]")) {
                int end = value.indexOf(']');
                String host = value.substring(1, end);
                if (end + 1 < value.length() && value.charAt(end + 1) == ':') {
                    int port = Integer.parseInt(value.substring(end + 2).trim());
                    return new HostPort(host, port);
                }
                return new HostPort(host, 25565);
            }

            int lastColon = value.lastIndexOf(':');
            int firstColon = value.indexOf(':');
            if (lastColon > 0 && firstColon == lastColon) {
                String host = value.substring(0, lastColon).trim();
                int port = Integer.parseInt(value.substring(lastColon + 1).trim());
                return new HostPort(host, port);
            }

            return new HostPort(value, 25565);
        }
    }

    public static final class ProxyHandle implements AutoCloseable {
        private final ServerSocket listener;
        private final AtomicBoolean running;
        private final Thread acceptThread;
        private final Mode mode;
        private final String remoteHost;
        private final int remotePort;
        private final ProxyStats stats;

        private ProxyHandle(
            ServerSocket listener,
            AtomicBoolean running,
            Thread acceptThread,
            Mode mode,
            String remoteHost,
            int remotePort,
            ProxyStats stats
        ) {
            this.listener = listener;
            this.running = running;
            this.acceptThread = acceptThread;
            this.mode = mode;
            this.remoteHost = remoteHost;
            this.remotePort = remotePort;
            this.stats = stats;
        }

        public int localPort() {
            return listener.getLocalPort();
        }

        public Mode mode() {
            return mode;
        }

        public String remoteHost() {
            return remoteHost;
        }

        public int remotePort() {
            return remotePort;
        }

        public StatsSnapshot statsSnapshot() {
            return stats.snapshot(mode, remoteHost, remotePort);
        }

        @Override
        public void close() {
            running.set(false);
            try {
                listener.close();
            } catch (IOException ignored) {
            }
            try {
                acceptThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public record StatsSnapshot(
        Mode mode,
        String remoteHost,
        int remotePort,
        long rawUpBytes,
        long rawDownBytes,
        long wireUpBytes,
        long wireDownBytes,
        long rawUpRate,
        long rawDownRate,
        long wireUpRate,
        long wireDownRate,
        double ratioPercent
    ) {
    }

    private static final class ProxyStats {
        private static final long RATE_SAMPLE_INTERVAL_MS = 500L;

        private final AtomicLong rawUpBytes = new AtomicLong();
        private final AtomicLong rawDownBytes = new AtomicLong();
        private final AtomicLong wireUpBytes = new AtomicLong();
        private final AtomicLong wireDownBytes = new AtomicLong();

        private volatile long sampleAtMs = System.currentTimeMillis();
        private volatile long sampledRawUpBytes;
        private volatile long sampledRawDownBytes;
        private volatile long sampledWireUpBytes;
        private volatile long sampledWireDownBytes;
        private volatile long rawUpRate;
        private volatile long rawDownRate;
        private volatile long wireUpRate;
        private volatile long wireDownRate;

        private void addClientToServerRaw(long bytes) {
            if (bytes > 0) {
                rawUpBytes.addAndGet(bytes);
            }
        }

        private void addServerToClientRaw(long bytes) {
            if (bytes > 0) {
                rawDownBytes.addAndGet(bytes);
            }
        }

        private void addClientToServerZstd(long bytes) {
            if (bytes > 0) {
                wireUpBytes.addAndGet(bytes);
            }
        }

        private void addServerToClientZstd(long bytes) {
            if (bytes > 0) {
                wireDownBytes.addAndGet(bytes);
            }
        }

        private void addClientToServerRawPassthrough(long bytes) {
            if (bytes > 0) {
                rawUpBytes.addAndGet(bytes);
                wireUpBytes.addAndGet(bytes);
            }
        }

        private void addServerToClientRawPassthrough(long bytes) {
            if (bytes > 0) {
                rawDownBytes.addAndGet(bytes);
                wireDownBytes.addAndGet(bytes);
            }
        }

        private synchronized StatsSnapshot snapshot(Mode mode, String remoteHost, int remotePort) {
            long now = System.currentTimeMillis();
            long currentRawUp = rawUpBytes.get();
            long currentRawDown = rawDownBytes.get();
            long currentWireUp = wireUpBytes.get();
            long currentWireDown = wireDownBytes.get();

            long elapsedMs = now - sampleAtMs;
            if (elapsedMs >= RATE_SAMPLE_INTERVAL_MS) {
                rawUpRate = scaleRate(currentRawUp - sampledRawUpBytes, elapsedMs);
                rawDownRate = scaleRate(currentRawDown - sampledRawDownBytes, elapsedMs);
                wireUpRate = scaleRate(currentWireUp - sampledWireUpBytes, elapsedMs);
                wireDownRate = scaleRate(currentWireDown - sampledWireDownBytes, elapsedMs);

                sampledRawUpBytes = currentRawUp;
                sampledRawDownBytes = currentRawDown;
                sampledWireUpBytes = currentWireUp;
                sampledWireDownBytes = currentWireDown;
                sampleAtMs = now;
            }

            long totalRaw = currentRawUp + currentRawDown;
            long totalWire = currentWireUp + currentWireDown;
            double ratio = totalRaw <= 0 ? 0.0D : (double) totalWire * 100.0D / (double) totalRaw;
            return new StatsSnapshot(
                mode,
                remoteHost,
                remotePort,
                currentRawUp,
                currentRawDown,
                currentWireUp,
                currentWireDown,
                rawUpRate,
                rawDownRate,
                wireUpRate,
                wireDownRate,
                ratio
            );
        }

        private long scaleRate(long deltaBytes, long elapsedMs) {
            if (deltaBytes <= 0 || elapsedMs <= 0) {
                return 0L;
            }
            return Math.max(0L, Math.round(deltaBytes * (1000.0D / elapsedMs)));
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final Counter counter;

        private CountingInputStream(InputStream delegate, Counter counter) {
            this.delegate = Objects.requireNonNull(delegate);
            this.counter = Objects.requireNonNull(counter);
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                counter.add(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                counter.add(read);
            }
            return read;
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final Counter counter;

        private CountingOutputStream(OutputStream delegate, Counter counter) {
            this.delegate = Objects.requireNonNull(delegate);
            this.counter = Objects.requireNonNull(counter);
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            counter.add(1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            if (len > 0) {
                counter.add(len);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

    @FunctionalInterface
    private interface Counter {
        void add(long bytes);
    }
}
