/*
 * Copyright (c) 2026 wish131400
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        return start(remoteHost, remotePort, remoteHost, remotePort, level, requestedMode);
    }

    public static ProxyHandle start(
        String remoteHost,
        int remotePort,
        String statusHost,
        int statusPort,
        int level,
        Mode requestedMode
    ) throws IOException {
        ServerSocket listener = new ServerSocket();
        listener.bind(new InetSocketAddress("127.0.0.1", 0));

        Mode resolvedMode = resolveMode(remoteHost, remotePort, requestedMode);
        AtomicBoolean running = new AtomicBoolean(true);
        Thread acceptThread = new Thread(
            () -> acceptLoop(listener, running, remoteHost, remotePort, statusHost, statusPort, level, resolvedMode),
            "zstdnet-accept-" + ACCEPT_SEQ.getAndIncrement() + "-" + remoteHost + ":" + remotePort
        );
        acceptThread.setDaemon(true);
        acceptThread.start();

        return new ProxyHandle(listener, running, acceptThread, resolvedMode);
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
        int level,
        Mode mode
    ) {
        while (running.get()) {
            try {
                Socket localClient = listener.accept();
                WORKERS.execute(() -> handleConnection(localClient, remoteHost, remotePort, statusHost, statusPort, level, mode));
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
        int level,
        Mode mode
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
            Integer nextState = extractHandshakeNextState(handshake);
            boolean isStatus = nextState != null && nextState == 1;

            if (isStatus) {
                handleRawConnection(localClient, statusHost, statusPort, handshake);
                return;
            }

            if (mode == Mode.RAW) {
                handleRawConnection(localClient, remoteHost, remotePort, handshake);
            } else {
                handleZstdConnection(localClient, remoteHost, remotePort, level, handshake);
            }
        } catch (Exception e) {
            LOGGER.debug("zstdnet: proxy pipe closed: {}", e.toString());
            closeQuietly(localClient);
        }
    }

    private static void handleRawConnection(Socket localClient, String remoteHost, int remotePort, byte[] firstPacket) throws Exception {
        try (Socket client = localClient; Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(0);

            OutputStream upstreamOut = upstream.getOutputStream();
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
                    streamCopy(upstream.getInputStream(), client.getOutputStream());
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

    private static void handleZstdConnection(Socket localClient, String remoteHost, int remotePort, int level, byte[] firstPacket) throws Exception {
        try (Socket client = localClient; Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(remoteHost, remotePort), 5000);
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(0);

            Future<?> upstreamWriter = WORKERS.submit(() -> {
                try (ZstdOutputStream zstdOut = new ZstdOutputStream(upstream.getOutputStream(), level)) {
                    zstdOut.setCloseFrameOnFlush(false);
                    writePacket(zstdOut, firstPacket);
                    zstdOut.flush();
                    streamCompress(client.getInputStream(), zstdOut);
                } catch (Exception ignored) {
                } finally {
                    try {
                        upstream.shutdownOutput();
                    } catch (Exception ignored) {
                    }
                }
            });

            Future<?> downstreamWriter = WORKERS.submit(() -> {
                try (ZstdInputStream zstdIn = new ZstdInputStream(upstream.getInputStream())) {
                    streamCopy(zstdIn, client.getOutputStream());
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

        private ProxyHandle(ServerSocket listener, AtomicBoolean running, Thread acceptThread, Mode mode) {
            this.listener = listener;
            this.running = running;
            this.acceptThread = acceptThread;
            this.mode = mode;
        }

        public int localPort() {
            return listener.getLocalPort();
        }

        public Mode mode() {
            return mode;
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
}
