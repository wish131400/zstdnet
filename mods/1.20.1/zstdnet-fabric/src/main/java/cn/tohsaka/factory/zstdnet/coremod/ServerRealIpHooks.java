package cn.tohsaka.factory.zstdnet.coremod;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

public final class ServerRealIpHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerRealIpHooks.class);
    private static final String MARKER = "\0zstdnet-real-ip\0";
    private static final String TOKEN_PREFIX = "zstdnet-real-ip=";
    private static final Map<Connection, SocketAddress> FORWARDED_ADDRESSES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<String> RECENT_RAW_HOST = new ThreadLocal<>();

    private ServerRealIpHooks() {
    }

    public static void applyForwardedAddress(Connection connection, ClientIntentionPacket packet) {
        SocketAddress backendAddress = connection == null ? null : connection.getRemoteAddress();
        if (connection == null || packet == null || !isTrustedLocalProxy(backendAddress)) {
            LOGGER.info("[zstdnet-server] skipped forwarded real IP hook because backend connection is not a trusted local proxy: {}", backendAddress);
            return;
        }

        String hostName = forwardedHostName(packet);
        String sourceIp = extractForwardedIp(hostName);
        if (sourceIp == null) {
            LOGGER.info("[zstdnet-server] no forwarded real IP marker in login handshake host '{}'", sanitizeHostForLog(hostName));
            return;
        }

        try {
            InetAddress address = InetAddress.getByName(sourceIp);
            int port = forwardedPort(backendAddress);
            SocketAddress forwarded = new InetSocketAddress(address, port);
            FORWARDED_ADDRESSES.put(connection, forwarded);
            replaceConnectionAddress(connection, forwarded);
            LOGGER.info("[zstdnet-server] forwarded backend connection address {} -> {}", backendAddress, forwarded);
        } catch (Exception e) {
            LOGGER.debug("[zstdnet-server] ignored invalid forwarded address '{}': {}", sourceIp, e.toString());
        }
    }

    public static boolean isForwardedConnection(Connection connection) {
        return connection != null && FORWARDED_ADDRESSES.containsKey(connection);
    }

    public static SocketAddress getRemoteAddress(Connection connection, SocketAddress fallback) {
        SocketAddress forwarded = FORWARDED_ADDRESSES.get(connection);
        return forwarded != null ? forwarded : fallback;
    }

    public static String rememberRawHandshakeHostString(String hostName) {
        RECENT_RAW_HOST.remove();
        if (hostName != null) {
            RECENT_RAW_HOST.set(hostName);
        }
        return hostName;
    }

    private static String extractForwardedIp(String hostName) {
        if (hostName == null) {
            return null;
        }

        String[] parts = hostName.split("\0", -1);
        for (String part : parts) {
            if (!part.startsWith(TOKEN_PREFIX)) {
                continue;
            }
            String encoded = part.substring(TOKEN_PREFIX.length()).trim();
            if (encoded.isEmpty() || encoded.length() > 96) {
                return null;
            }
            try {
                return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }

        int markerIndex = hostName.lastIndexOf(MARKER);
        if (markerIndex < 0) {
            return null;
        }

        String value = hostName.substring(markerIndex + MARKER.length()).trim();
        if (value.isEmpty() || value.indexOf('\0') >= 0 || value.length() > 64) {
            return null;
        }
        return value;
    }

    private static String sanitizeHostForLog(String hostName) {
        if (hostName == null) {
            return "<null>";
        }
        String sanitized = hostName.replace('\0', '|');
        if (sanitized.length() > 120) {
            return sanitized.substring(0, 120) + "...";
        }
        return sanitized;
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

    private static String forwardedHostName(ClientIntentionPacket packet) {
        String threadLocalHost = RECENT_RAW_HOST.get();
        RECENT_RAW_HOST.remove();
        if (threadLocalHost != null) {
            if (hasForwardedMarker(threadLocalHost)) {
                return threadLocalHost;
            }
        }

        for (String methodName : new String[] {"hostName", "getHostName"}) {
            try {
                Method method = packet.getClass().getMethod(methodName);
                Object value = method.invoke(packet);
                if (value instanceof String hostName) {
                    return hostName;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }

        for (String fieldName : new String[] {"hostName", "host"}) {
            try {
                Field field = packet.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(packet);
                if (value instanceof String hostName) {
                    return hostName;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static boolean hasForwardedMarker(String hostName) {
        return hostName != null && (hostName.contains(TOKEN_PREFIX) || hostName.contains(MARKER));
    }

    private static boolean isTrustedLocalProxy(SocketAddress address) {
        if (!(address instanceof InetSocketAddress inet)) {
            return false;
        }

        InetAddress ip = inet.getAddress();
        return ip != null && (ip.isLoopbackAddress() || ip.isAnyLocalAddress());
    }

    private static int forwardedPort(SocketAddress address) {
        if (address instanceof InetSocketAddress inet) {
            return inet.getPort();
        }
        return 0;
    }
}
