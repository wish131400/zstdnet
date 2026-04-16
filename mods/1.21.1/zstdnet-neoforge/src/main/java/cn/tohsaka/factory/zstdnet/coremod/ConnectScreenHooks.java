package cn.tohsaka.factory.zstdnet.coremod;

import cn.tohsaka.factory.zstdnet.ClientConfig;
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class ConnectScreenHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectScreenHooks.class);
    private static volatile boolean bypassing;
    private static volatile LocalZstdNet.ProxyHandle currentProxy;
    private static final Object LOCK = new Object();

    private ConnectScreenHooks() {
    }

    public static void setBypass(boolean bypass) {
        bypassing = bypass;
    }

    public static ServerAddress interceptConnect(ServerAddress original, ServerData serverData) {
        if (bypassing) {
            bypassing = false;
            return original;
        }

        if (original == null) {
            return original;
        }

        String host = original.getHost();
        if ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)) {
            return original;
        }

        String remoteAddr = host + ":" + original.getPort();
        ResolvedServerAddress resolved = ServerNameResolver.DEFAULT.resolveAddress(original).orElse(null);
        if (resolved == null) {
            return original;
        }

        String connectHost = resolved.asInetSocketAddress().getHostString();
        if (connectHost == null || connectHost.isBlank()) {
            connectHost = resolved.getHostName();
        }
        if (connectHost == null || connectHost.isBlank()) {
            connectHost = host;
        }

        try {
            synchronized (LOCK) {
                closeCurrentProxy();
                LocalZstdNet.ProxyHandle proxy = LocalZstdNet.start(
                    connectHost,
                    resolved.getPort(),
                    connectHost,
                    resolved.getPort(),
                    connectHost,
                    resolved.getPort(),
                    ClientConfig.getLevel(),
                    LocalZstdNet.Mode.ZSTD
                );
                currentProxy = proxy;
                String localAddr = "127.0.0.1:" + proxy.localPort();
                LOGGER.info("zstdnet: intercepted programmatic connect {} -> local {}", remoteAddr, localAddr);

                if (serverData != null) {
                    serverData.ip = remoteAddr;
                }

                return ServerAddress.parseString(localAddr);
            }
        } catch (IOException e) {
            LOGGER.warn("zstdnet: failed to start proxy for intercepted connect {}: {}", remoteAddr, e.toString());
            return original;
        }
    }

    public static LocalZstdNet.ProxyHandle takeProxy() {
        synchronized (LOCK) {
            LocalZstdNet.ProxyHandle proxy = currentProxy;
            currentProxy = null;
            return proxy;
        }
    }

    private static void closeCurrentProxy() {
        if (currentProxy != null) {
            try {
                currentProxy.close();
            } catch (Exception ignored) {
            }
            currentProxy = null;
        }
    }
}
