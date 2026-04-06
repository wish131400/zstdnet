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
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.LanServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Client-side runtime that keeps vanilla server entries untouched and only swaps
 * the actual connect target to a temporary local zstd proxy when the player joins.
 */
public final class ClientProxyPublisher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ClientProxyPublisher INSTANCE = new ClientProxyPublisher();

    private final Object stateLock = new Object();
    private final Map<JoinMultiplayerScreen, JoinScreenState> joinScreens = new WeakHashMap<>();
    private final Map<DirectJoinServerScreen, DirectJoinState> directJoinScreens = new WeakHashMap<>();

    private LocalZstdNet.ProxyHandle activeProxy;
    private Object lastListEntry;
    private long lastListClickTime;

    private ClientProxyPublisher() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "zstdproxy-client-shutdown"));
    }

    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenInit);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenClosing);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onKeyPressed);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onMousePressed);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenOpening);
        LOGGER.info("zstdproxy client runtime initialized");
    }

    private void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        List<?> listeners = event.getListenersList();

        if (screen instanceof JoinMultiplayerScreen joinScreen) {
            JoinScreenState state = JoinScreenState.from(listeners);
            synchronized (stateLock) {
                joinScreens.put(joinScreen, state);
            }
            LOGGER.debug("zstdproxy: hooked multiplayer screen");
            return;
        }

        if (screen instanceof DirectJoinServerScreen directJoinScreen) {
            DirectJoinState state = DirectJoinState.from(listeners);
            synchronized (stateLock) {
                directJoinScreens.put(directJoinScreen, state);
            }
            LOGGER.debug("zstdproxy: hooked direct-join screen");
        }
    }

    private void onScreenClosing(ScreenEvent.Closing event) {
        Screen screen = event.getScreen();
        synchronized (stateLock) {
            if (screen instanceof JoinMultiplayerScreen joinScreen) {
                joinScreens.remove(joinScreen);
            } else if (screen instanceof DirectJoinServerScreen directJoinScreen) {
                directJoinScreens.remove(directJoinScreen);
            }
        }
    }

    private void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getCurrentScreen() instanceof ConnectScreen && event.getNewScreen() != null) {
            closeActiveProxy();
        }
    }

    private void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();

        if (screen instanceof JoinMultiplayerScreen joinScreen) {
            if (!net.minecraft.client.gui.navigation.CommonInputs.selected(event.getKeyCode())) {
                return;
            }
            if (connectSelected(joinScreen)) {
                event.setCanceled(true);
            }
            return;
        }

        if (screen instanceof DirectJoinServerScreen directJoinScreen) {
            if (event.getKeyCode() != 257 && event.getKeyCode() != 335) {
                return;
            }
            DirectJoinState state = getDirectJoinState(directJoinScreen);
            if (state == null || state.ipEdit == null || state.selectButton == null || !state.selectButton.active) {
                return;
            }
            if (screen.getFocused() != state.ipEdit) {
                return;
            }
            if (connectDirect(directJoinScreen, state)) {
                event.setCanceled(true);
            }
        }
    }

    private void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) {
            return;
        }

        Screen screen = event.getScreen();

        if (screen instanceof JoinMultiplayerScreen joinScreen) {
            JoinScreenState state = getJoinScreenState(joinScreen);
            if (state == null) {
                return;
            }

            if (state.selectButton != null && state.selectButton.active && state.selectButton.isMouseOver(event.getMouseX(), event.getMouseY())) {
                if (connectSelected(joinScreen)) {
                    event.setCanceled(true);
                }
                return;
            }

            if (state.serverList == null || !state.serverList.isMouseOver(event.getMouseX(), event.getMouseY())) {
                return;
            }

            ServerSelectionList.Entry entry = hoveredEntry(state.serverList, event.getMouseX(), event.getMouseY());
            if (entry == null) {
                return;
            }

            long now = System.currentTimeMillis();
            boolean isDoubleClick = entry == lastListEntry && (now - lastListClickTime) < 250L;
            double rowOffsetX = event.getMouseX() - state.serverList.getRowLeft();
            boolean isJoinIconClick = entry instanceof ServerSelectionList.OnlineServerEntry
                && rowOffsetX > 16.0D
                && rowOffsetX < 32.0D;
            lastListEntry = entry;
            lastListClickTime = now;

            if (!isDoubleClick && !isJoinIconClick) {
                return;
            }

            if (connectEntry(joinScreen, entry)) {
                event.setCanceled(true);
            }
            return;
        }

        if (screen instanceof DirectJoinServerScreen directJoinScreen) {
            DirectJoinState state = getDirectJoinState(directJoinScreen);
            if (state == null || state.selectButton == null || !state.selectButton.active) {
                return;
            }
            if (state.selectButton.isMouseOver(event.getMouseX(), event.getMouseY()) && connectDirect(directJoinScreen, state)) {
                event.setCanceled(true);
            }
        }
    }

    private boolean connectSelected(JoinMultiplayerScreen screen) {
        JoinScreenState state = getJoinScreenState(screen);
        if (state == null || state.serverList == null) {
            return false;
        }
        ServerSelectionList.Entry entry = state.serverList.getSelected();
        return connectEntry(screen, entry);
    }

    private boolean connectEntry(Screen parent, ServerSelectionList.Entry entry) {
        if (entry instanceof ServerSelectionList.OnlineServerEntry onlineEntry) {
            return connect(parent, onlineEntry.getServerData());
        }
        if (entry instanceof ServerSelectionList.NetworkServerEntry networkEntry) {
            LanServer lanServer = networkEntry.getServerData();
            return connect(parent, new ServerData(lanServer.getMotd(), lanServer.getAddress(), true));
        }
        return false;
    }

    private boolean connectDirect(DirectJoinServerScreen screen, DirectJoinState state) {
        if (state.ipEdit == null) {
            return false;
        }
        String raw = normalizeAddress(state.ipEdit.getValue());
        if (raw.isEmpty() || !ServerAddress.isValidAddress(raw)) {
            return false;
        }
        return connect(screen, resolveDirectJoinServer(raw));
    }

    private boolean connect(Screen parent, ServerData serverData) {
        String remoteAddr = normalizeAddress(serverData.ip);
        if (remoteAddr.isEmpty() || !ServerAddress.isValidAddress(remoteAddr)) {
            LOGGER.warn("zstdproxy: invalid remote address {}", serverData.ip);
            return false;
        }

        RemoteTarget remote = resolveRemoteTarget(remoteAddr);
        if (remote == null) {
            LOGGER.warn("zstdproxy: failed to resolve remote target {}", remoteAddr);
            return false;
        }
        LocalZstdNet.ProxyHandle proxy;

        try {
            synchronized (stateLock) {
                closeActiveProxyLocked();
                proxy = LocalZstdNet.start(
                    remote.connectHost(),
                    remote.connectPort(),
                    remote.connectHost(),
                    remote.connectPort(),
                    remote.presentedHost(),
                    remote.presentedPort(),
                    ClientConfig.getLevel(),
                    LocalZstdNet.Mode.ZSTD
                );
                activeProxy = proxy;
            }
        } catch (IOException e) {
            LOGGER.error("zstdproxy: failed to start local proxy for {}", remoteAddr, e);
            return false;
        }

        serverData.ip = remoteAddr;
        String localAddr = "127.0.0.1:" + proxy.localPort();
        LOGGER.info("zstdproxy: {} -> {} via local {}", safe(serverData.name), remoteAddr, localAddr);
        ConnectScreen.startConnecting(parent, Minecraft.getInstance(), ServerAddress.parseString(localAddr), serverData, false);
        return true;
    }

    private JoinScreenState getJoinScreenState(JoinMultiplayerScreen screen) {
        synchronized (stateLock) {
            return joinScreens.get(screen);
        }
    }

    private DirectJoinState getDirectJoinState(DirectJoinServerScreen screen) {
        synchronized (stateLock) {
            return directJoinScreens.get(screen);
        }
    }

    private ServerSelectionList.Entry hoveredEntry(ServerSelectionList list, double mouseX, double mouseY) {
        for (ServerSelectionList.Entry entry : list.children()) {
            if (entry != null && entry.isMouseOver(mouseX, mouseY)) {
                return entry;
            }
        }
        return null;
    }

    private void closeActiveProxy() {
        synchronized (stateLock) {
            closeActiveProxyLocked();
        }
    }

    private void closeActiveProxyLocked() {
        if (activeProxy == null) {
            return;
        }
        try {
            activeProxy.close();
        } catch (Exception ignored) {
        }
        activeProxy = null;
    }

    private void shutdown() {
        closeActiveProxy();
    }

    private ServerData resolveDirectJoinServer(String remoteAddr) {
        Minecraft minecraft = Minecraft.getInstance();
        ServerList serverList = new ServerList(minecraft);
        serverList.load();

        ServerData existing = serverList.get(remoteAddr);
        if (existing != null) {
            return existing;
        }

        ServerData created = new ServerData(I18n.get("selectServer.defaultName"), remoteAddr, false);
        serverList.add(created, true);
        serverList.save();
        return created;
    }

    private RemoteTarget resolveRemoteTarget(String remoteAddr) {
        ServerAddress requested = ServerAddress.parseString(remoteAddr);
        if (requested == null || requested.getHost().isBlank()) {
            return null;
        }

        ResolvedServerAddress resolved = ServerNameResolver.DEFAULT.resolveAddress(requested).orElse(null);
        if (resolved == null) {
            return null;
        }

        String connectHost = resolved.asInetSocketAddress().getHostString();
        if (connectHost == null || connectHost.isBlank()) {
            connectHost = resolved.getHostName();
        }
        if (connectHost == null || connectHost.isBlank()) {
            connectHost = requested.getHost();
        }

        return new RemoteTarget(
            connectHost,
            resolved.getPort(),
            connectHost,
            resolved.getPort()
        );
    }

    private static String normalizeAddress(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static boolean isSelectButton(Button button) {
        return button != null && Objects.equals(button.getMessage().getString(), I18n.get("selectServer.select"));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unnamed" : value.trim();
    }

    private static final class JoinScreenState {
        private final ServerSelectionList serverList;
        private final Button selectButton;

        private JoinScreenState(ServerSelectionList serverList, Button selectButton) {
            this.serverList = serverList;
            this.selectButton = selectButton;
        }

        private static JoinScreenState from(List<?> listeners) {
            ServerSelectionList list = null;
            Button select = null;

            for (Object listener : listeners) {
                if (listener instanceof ServerSelectionList foundList) {
                    list = foundList;
                } else if (listener instanceof Button button && isSelectButton(button)) {
                    select = button;
                }
            }

            return new JoinScreenState(list, select);
        }
    }

    private static final class DirectJoinState {
        private final EditBox ipEdit;
        private final Button selectButton;

        private DirectJoinState(EditBox ipEdit, Button selectButton) {
            this.ipEdit = ipEdit;
            this.selectButton = selectButton;
        }

        private static DirectJoinState from(List<?> listeners) {
            EditBox ipEdit = null;
            Button select = null;

            for (Object listener : listeners) {
                if (listener instanceof EditBox editBox) {
                    ipEdit = editBox;
                } else if (listener instanceof Button button && isSelectButton(button)) {
                    select = button;
                }
            }

            return new DirectJoinState(ipEdit, select);
        }
    }

    private record RemoteTarget(String connectHost, int connectPort, String presentedHost, int presentedPort) {
    }
}
