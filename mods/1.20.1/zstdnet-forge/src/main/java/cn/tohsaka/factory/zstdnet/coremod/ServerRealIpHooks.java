package cn.tohsaka.factory.zstdnet.coremod;

import cn.tohsaka.factory.zstdnet.core.transport.ConnectionFloodGuard;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;
import io.netty.handler.codec.ByteToMessageDecoder;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

public final class ServerRealIpHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerRealIpHooks.class);
    private static final Map<Connection, SocketAddress> FORWARDED_ADDRESSES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final byte[] PROXY_V2_SIGNATURE = {
        0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A
    };
    private static final int MAX_PROXY_V2_LENGTH = 4096;
    private static volatile boolean proxyProtocolEnabled;
    private static volatile boolean debugLogging;
    private static volatile ConnectionFloodGuard floodGuard;
    private static volatile Set<InetAddress> trustedProxyIps = Set.of();

    private ServerRealIpHooks() {
    }

    public static void configureFloodGuard(ConnectionFloodGuard guard) {
        floodGuard = guard;
    }

    public static void configureDebugLogging(boolean enabled) {
        debugLogging = enabled;
    }

    public static void configureProxyProtocol(boolean enabled, Collection<String> trustedIps) {
        Set<InetAddress> parsed = new HashSet<>();
        if (trustedIps != null) {
            for (String value : trustedIps) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                try {
                    parsed.add(InetAddress.getByName(value.trim().replace("[", "").replace("]", "")));
                } catch (Exception ignored) {
                    LOGGER.warn("[zstdnet-server] ignored invalid trusted proxy IP '{}'", value);
                }
            }
        }
        trustedProxyIps = Set.copyOf(parsed);
        proxyProtocolEnabled = enabled;
    }

    public static void installProxyProtocol(Connection connection, ChannelHandlerContext context) {
        if (connection == null || context == null || connection.getReceiving() != PacketFlow.SERVERBOUND
                || !(context.channel().remoteAddress() instanceof InetSocketAddress)
                || context.pipeline().get("zstdnet_proxy_protocol") != null) {
            return;
        }
        if (context.pipeline().get("splitter") != null) {
            context.pipeline().addBefore("splitter", "zstdnet_proxy_protocol", new ProxyProtocolDecoder(connection));
        } else {
            context.pipeline().addFirst("zstdnet_proxy_protocol", new ProxyProtocolDecoder(connection));
        }
        ConnectionFloodGuard guard = floodGuard;
        if (guard != null && connection.getReceiving() == PacketFlow.SERVERBOUND
                && context.channel().remoteAddress() instanceof InetSocketAddress) {
            context.pipeline().addAfter("zstdnet_proxy_protocol", "zstdnet_flood_guard", new FloodGuardHandler(connection, guard));
        }
    }

    private static final class FloodGuardHandler extends ChannelInboundHandlerAdapter {
        private final Connection connection;
        private final ConnectionFloodGuard guard;
        private String guardedIp;

        private FloodGuardHandler(Connection connection, ConnectionFloodGuard guard) {
            this.connection = connection;
            this.guard = guard;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
            if (guardedIp == null) {
                SocketAddress address = connection.getRemoteAddress();
                if (!(address instanceof InetSocketAddress inet) || inet.getAddress() == null) {
                    ReferenceCountUtil.release(message);
                    ctx.close();
                    return;
                }
                String ip = inet.getAddress().getHostAddress();
                if (!guard.begin(ip)) {
                    ReferenceCountUtil.release(message);
                    ctx.close();
                    return;
                }
                guardedIp = ip;
            }
            super.channelRead(ctx, message);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (guardedIp != null) {
                guard.end(guardedIp);
                guardedIp = null;
            }
            super.channelInactive(ctx);
        }
    }

    public static SocketAddress getRemoteAddress(Connection connection, SocketAddress fallback) {
        SocketAddress forwarded = FORWARDED_ADDRESSES.get(connection);
        return forwarded != null ? forwarded : fallback;
    }

    private static void replaceConnectionAddress(Connection connection, SocketAddress forwarded) {
        Class<?> type = connection.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (SocketAddress.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        field.set(connection, forwarded);
                        return;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        LOGGER.debug("[zstdnet-server] failed to replace Connection address field {}: {}", field.getName(), e.toString());
                    }
                }
            }
            type = type.getSuperclass();
        }
        LOGGER.warn("[zstdnet-server] could not find Connection SocketAddress field to replace.");
    }

    private static int forwardedPort(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.getPort();
        }
        return 0;
    }

    private static boolean isTrustedProxyPeer(SocketAddress address) {
        return address instanceof InetSocketAddress inet
            && inet.getAddress() != null
            && trustedProxyIps.contains(inet.getAddress());
    }

    private static boolean isLoopbackPeer(SocketAddress address) {
        return address instanceof InetSocketAddress inet
            && inet.getAddress() != null
            && (inet.getAddress().isLoopbackAddress() || inet.getAddress().isAnyLocalAddress());
    }

    private static void applyProxyAddress(Connection connection, InetAddress source, int port) {
        SocketAddress backend = connection.getRemoteAddress();
        SocketAddress forwarded = new InetSocketAddress(source, port > 0 ? port : forwardedPort(backend));
        FORWARDED_ADDRESSES.put(connection, forwarded);
        replaceConnectionAddress(connection, forwarded);
        if (debugLogging) {
            LOGGER.info("[zstdnet-server] PROXY v2 forwarded backend connection address {} -> {}", backend, forwarded);
        }
    }

    private static final class ProxyProtocolDecoder extends ByteToMessageDecoder {
        private final Connection connection;
        private boolean decided;

        private ProxyProtocolDecoder(Connection connection) {
            this.connection = connection;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, java.util.List<Object> out) {
            if (decided || in.readableBytes() < PROXY_V2_SIGNATURE.length) {
                return;
            }
            int start = in.readerIndex();
            boolean signature = true;
            for (int i = 0; i < PROXY_V2_SIGNATURE.length; i++) {
                if (in.getByte(start + i) != PROXY_V2_SIGNATURE[i]) {
                    signature = false;
                    break;
                }
            }
            SocketAddress peer = connection.getRemoteAddress();
            if (!signature) {
                decided = true;
                if (!proxyProtocolEnabled || isLoopbackPeer(peer)) {
                    ctx.pipeline().remove(this);
                    out.add(in.readRetainedSlice(in.readableBytes()));
                } else {
                    ctx.close();
                }
                return;
            }
            if (!proxyProtocolEnabled) {
                LOGGER.warn("[zstdnet-server] rejected PROXY v2 header from {} because support is disabled", peer);
                ctx.close();
                decided = true;
                return;
            }
            if (!isTrustedProxyPeer(peer) || in.readableBytes() < 16) {
                if (!isTrustedProxyPeer(peer)) {
                    ctx.close();
                    decided = true;
                }
                return;
            }
            int versionCommand = in.getUnsignedByte(start + 12);
            int familyProtocol = in.getUnsignedByte(start + 13);
            int payloadLength = in.getUnsignedShort(start + 14);
            if ((versionCommand & 0xF0) != 0x20 || (versionCommand & 0x0F) > 1 || payloadLength > MAX_PROXY_V2_LENGTH
                    || in.readableBytes() < 16 + payloadLength) {
                if (in.readableBytes() < 16 + payloadLength && payloadLength <= MAX_PROXY_V2_LENGTH) {
                    return;
                }
                ctx.close();
                decided = true;
                return;
            }
            int addressBytes = switch (familyProtocol) {
                case 0x11 -> 12;
                case 0x21 -> 36;
                case 0x00, 0x20 -> 0;
                default -> -1;
            };
            if (addressBytes < 0 || payloadLength < addressBytes) {
                ctx.close();
                decided = true;
                return;
            }
            int payloadIndex = start + 16;
            if ((versionCommand & 0x0F) == 1 && addressBytes > 0) {
                try {
                    byte[] sourceBytes = new byte[familyProtocol == 0x11 ? 4 : 16];
                    in.getBytes(payloadIndex, sourceBytes);
                    int sourcePort = in.getUnsignedShort(payloadIndex + (familyProtocol == 0x11 ? 8 : 32));
                    applyProxyAddress(connection, InetAddress.getByAddress(sourceBytes), sourcePort);
                } catch (Exception e) {
                    ctx.close();
                    decided = true;
                    return;
                }
            }
            in.skipBytes(16 + payloadLength);
            decided = true;
            ctx.pipeline().remove(this);
            if (in.isReadable()) {
                out.add(in.readRetainedSlice(in.readableBytes()));
            }
        }
    }
}
