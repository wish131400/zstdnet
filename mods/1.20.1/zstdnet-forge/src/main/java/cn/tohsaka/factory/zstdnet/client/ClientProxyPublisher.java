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

package cn.tohsaka.factory.zstdnet.client;

import cn.tohsaka.factory.zstdnet.ClientConfig;
import cn.tohsaka.factory.zstdnet.ZstdServerList;
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publishes simplified local loopback entries from servers.zstd.json.
 */
public final class ClientProxyPublisher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final ClientProxyPublisher INSTANCE = new ClientProxyPublisher();

    private final Map<String, LocalZstdNet.ProxyHandle> runningProxies = new ConcurrentHashMap<>();
    private final Map<String, Integer> proxyPortMap = new ConcurrentHashMap<>();
    private final List<ZstdServerList.ZstdServer> activeServers = new ArrayList<>();

    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "zstdproxy-refresh");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean refreshStarted = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private volatile String lastServerRaw = null;
    private volatile int lastLevel = Integer.MIN_VALUE;

    private ClientProxyPublisher() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "zstdproxy-client-shutdown"));
    }

    public static void init() {
        var modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(INSTANCE::onLoadComplete);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        LOGGER.info("zstdproxy client mode initialized");
    }

    private void onLoadComplete(FMLLoadCompleteEvent event) {
        startAutoRefresh();
    }

    private void startAutoRefresh() {
        if (stopped.get()) {
            return;
        }
        if (!refreshStarted.compareAndSet(false, true)) {
            return;
        }

        refresher.scheduleAtFixedRate(() -> {
            if (stopped.get()) {
                return;
            }
            try {
                reloadAndPublishIfChanged();
            } catch (Exception e) {
                LOGGER.error("zstdproxy: refresh tick failed", e);
            }
        }, 0, 2, TimeUnit.SECONDS);

        LOGGER.info("zstdproxy: auto refresh enabled (2s)");
    }

    private synchronized void reloadAndPublishIfChanged() {
        String raw = readServerRaw();
        int level = ClientConfig.getLevel();

        if (blank(raw)) {
            resetPublishedServers(level);
            return;
        }

        if (raw.equals(lastServerRaw) && level == lastLevel) {
            return;
        }

        List<ZstdServerList.ZstdServer> configured = parseServers(raw);
        lastServerRaw = raw;
        lastLevel = level;

        closeAllProxies();
        activeServers.clear();
        activeServers.addAll(configured);

        for (ZstdServerList.ZstdServer server : configured) {
            if (server == null || blank(server.mask()) || blank(server.addr())) {
                continue;
            }

            try {
                LocalZstdNet.HostPort hostPort = LocalZstdNet.HostPort.parse(server.addr());
                LocalZstdNet.Mode mode = parseMode(server.mode());
                LocalZstdNet.ProxyHandle proxy = LocalZstdNet.start(hostPort.host(), hostPort.port(), level, mode);
                runningProxies.put(server.mask(), proxy);
                proxyPortMap.put(server.mask(), proxy.localPort());
                LOGGER.info("zstd server {} ({}) [{}] -> 127.0.0.1:{}",
                    safe(server.name()), server.mask(), proxy.mode(), proxy.localPort());
            } catch (Exception e) {
                LOGGER.error("failed to start local proxy for {} ({})", safe(server.name()), safe(server.addr()), e);
            }
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(this::updateServerList);
        }
    }

    private void resetPublishedServers(int level) {
        if ("".equals(lastServerRaw) && lastLevel == level) {
            return;
        }

        lastServerRaw = "";
        lastLevel = level;
        closeAllProxies();
        activeServers.clear();

        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.execute(this::updateServerList);
        }

        LOGGER.warn("zstdproxy: no server source loaded");
    }

    private List<ZstdServerList.ZstdServer> parseServers(String raw) {
        try {
            ZstdServerList list = GSON.fromJson(raw, ZstdServerList.class);
            if (list == null || list.servers() == null) {
                return List.of();
            }
            return list.servers();
        } catch (Exception e) {
            LOGGER.error("zstdproxy: failed to parse servers.zstd.json", e);
            return List.of();
        }
    }

    private String readServerRaw() {
        Path gameDirFile = FMLPaths.GAMEDIR.get().resolve("servers.zstd.json");
        if (Files.exists(gameDirFile)) {
            try {
                LOGGER.debug("zstdproxy: loading {}", gameDirFile);
                return Files.readString(gameDirFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("zstdproxy: failed reading {}", gameDirFile, e);
            }
        } else {
            createTemplateIfMissing(gameDirFile);
        }

        Path cwdFile = Path.of("servers.zstd.json");
        if (Files.exists(cwdFile)) {
            try {
                LOGGER.debug("zstdproxy: loading {}", cwdFile.toAbsolutePath());
                return Files.readString(cwdFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOGGER.error("zstdproxy: failed reading {}", cwdFile.toAbsolutePath(), e);
            }
        }

        return "";
    }

    private void createTemplateIfMissing(Path target) {
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            String template = """
                    {
                      "_comment": "zstd server list template. Fill addr/mask as needed.",
                      "servers": [
                        {
                          "name": "\\u8bf7\\u586b\\u5199\\u670d\\u52a1\\u5668\\u5730\\u5740\\u540e\\u7b49\\u5f855\\u79d2",
                          "addr": "example.com:35566",
                          "mask": "line1",
                          "mode": "zstd",
                          "_comment_mode": "auto/raw/zstd. zstd will probe first."
                        }
                      ]
                    }
                    """;
            Files.writeString(target, template, StandardCharsets.UTF_8);
            LOGGER.info("zstdproxy: generated template {}", target.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.error("zstdproxy: failed to generate template {}", target.toAbsolutePath(), e);
        }
    }

    private void updateServerList() {
        try {
            ServerList serverList = new ServerList(Minecraft.getInstance());
            serverList.load();

            for (int i = serverList.size() - 1; i >= 0; i--) {
                ServerData existing = serverList.get(i);
                if (existing != null && existing.name != null && existing.name.endsWith(" [zstd]")) {
                    serverList.remove(existing);
                }
            }

            for (ZstdServerList.ZstdServer server : activeServers) {
                if (server == null || blank(server.mask())) {
                    continue;
                }

                Integer localPort = proxyPortMap.get(server.mask());
                if (localPort == null) {
                    continue;
                }

                String title = safe(server.name()) + " [zstd]";
                String addr = "127.0.0.1:" + localPort;
                ServerData data = new ServerData(title, addr, false);
                serverList.add(data, false);
            }

            serverList.save();
            LOGGER.info("zstdproxy: server list updated, {} zstd entries", activeServers.size());
        } catch (Exception e) {
            LOGGER.error("zstdproxy: failed to update multiplayer entries", e);
        }
    }

    private synchronized void closeAllProxies() {
        for (LocalZstdNet.ProxyHandle handle : runningProxies.values()) {
            if (handle != null) {
                try {
                    handle.close();
                } catch (Exception ignored) {
                }
            }
        }
        runningProxies.clear();
        proxyPortMap.clear();
    }

    private void shutdown() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        refresher.shutdownNow();
        synchronized (this) {
            closeAllProxies();
            activeServers.clear();
        }
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(String s) {
        if (blank(s)) {
            return "unnamed";
        }
        return s.trim();
    }

    private static LocalZstdNet.Mode parseMode(String raw) {
        if (blank(raw)) {
            return LocalZstdNet.Mode.ZSTD;
        }

        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "raw" -> LocalZstdNet.Mode.RAW;
            case "auto" -> LocalZstdNet.Mode.AUTO;
            default -> LocalZstdNet.Mode.ZSTD;
        };
    }
}
