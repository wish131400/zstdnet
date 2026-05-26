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

package cn.tohsaka.factory.zstdnet.client;

import cn.tohsaka.factory.zstdnet.ClientConfig;
import cn.tohsaka.factory.zstdnet.coremod.ConnectScreenHooks;
import cn.tohsaka.factory.zstdnet.proxy.LocalZstdNet;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import cn.tohsaka.factory.zstdnet.server.ServerProxyConfigFile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.LanServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.HttpUtil;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Client-side runtime that keeps vanilla server entries untouched and only swaps
 * the actual connect target to a temporary local zstd proxy when the player joins.
 */
public final class ClientProxyPublisher {
    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;
    private static final int DEFAULT_BACKEND_PORT = 25566;
    private static final int PORT_TEXT_NORMAL = 14737632;
    private static final int PORT_TEXT_INVALID = 16733525;
    private static final int HUD_SERVER_BACKGROUND = 0x90241208;
    private static final int HUD_SERVER_TITLE = 0xFFE1A3;
    private static final int HUD_SERVER_TEXT = 0xFFD08A;
    private static final int HUD_CLIENT_BACKGROUND = 0x90081224;
    private static final int HUD_CLIENT_TITLE = 0xA8E6FF;
    private static final int HUD_CLIENT_TEXT = 0x8FD3FF;
    private static final long REMOTE_HUD_SNAPSHOT_TTL_MS = 3000L;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ClientProxyPublisher INSTANCE = new ClientProxyPublisher();
    private static final Component BACKEND_PORT_LABEL = Component.translatable("zstdnet.share_to_lan.backend_port");
    private static final Component ZSTD_PORT_LABEL = Component.translatable("zstdnet.share_to_lan.zstd_port");
    private static final Component ZSTD_PORT_HELP = Component.translatable("zstdnet.share_to_lan.port_help");
    private static final Component ZSTD_PORT_INVALID = Component.translatable("zstdnet.share_to_lan.port_invalid");
    private static final Component ZSTD_PORT_UNAVAILABLE = Component.translatable("zstdnet.share_to_lan.port_unavailable");

    private final Object stateLock = new Object();
    private final Map<JoinMultiplayerScreen, JoinScreenState> joinScreens = new WeakHashMap<>();
    private final Map<DirectJoinServerScreen, DirectJoinState> directJoinScreens = new WeakHashMap<>();
    private final Map<Screen, ShareToLanState> shareToLanScreens = new WeakHashMap<>();

    private LocalZstdNet.ProxyHandle activeProxy;
    private LocalZstdNet.ProxyHandle activeSession;
    private boolean hudVisible = false;
    private boolean singleplayerLanHintShown;
    private Object lastListEntry;
    private long lastListClickTime;
    private ServerProxyBootstrap.ServerHudSnapshot remoteServerHudSnapshot;
    private long remoteServerHudSnapshotMillis;

    private ClientProxyPublisher() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "zstdnet-client-shutdown"));
    }

    public static void init() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenInit);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenClosing);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onKeyPressed);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onMousePressed);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenOpening);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onClientLogin);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onClientLogout);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onRenderGui);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenRenderPre);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onScreenRender);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onClientTick);
        MinecraftForge.EVENT_BUS.addListener(INSTANCE::onRegisterClientCommands);
        LOGGER.info("zstdnet client runtime initialized");
    }

    public static void acceptRemoteServerHudSnapshot(ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        INSTANCE.updateRemoteServerHudSnapshot(snapshot);
    }

    private void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        List<?> listeners = event.getListenersList();

        if (screen instanceof JoinMultiplayerScreen joinScreen) {
            JoinScreenState state = JoinScreenState.from(listeners);
            synchronized (stateLock) {
                joinScreens.put(joinScreen, state);
            }
            LOGGER.debug("zstdnet: hooked multiplayer screen");
            return;
        }

        if (screen instanceof DirectJoinServerScreen directJoinScreen) {
            DirectJoinState state = DirectJoinState.from(listeners);
            synchronized (stateLock) {
                directJoinScreens.put(directJoinScreen, state);
            }
            LOGGER.debug("zstdnet: hooked direct-join screen");
            return;
        }

        if (isShareToLanLikeScreen(screen)) {
            ShareToLanState state = attachShareToLanState(screen, event, listeners);
            if (state != null) {
                synchronized (stateLock) {
                    shareToLanScreens.put(screen, state);
                }
                LOGGER.debug("zstdnet: hooked share-to-lan screen {}", screen.getClass().getName());
            }
        }
    }

    private void onScreenClosing(ScreenEvent.Closing event) {
        Screen screen = event.getScreen();
        synchronized (stateLock) {
            if (screen instanceof JoinMultiplayerScreen joinScreen) {
                joinScreens.remove(joinScreen);
            } else if (screen instanceof DirectJoinServerScreen directJoinScreen) {
                directJoinScreens.remove(directJoinScreen);
            } else if (isShareToLanLikeScreen(screen)) {
                shareToLanScreens.remove(screen);
            }
        }
    }

    private void onScreenOpening(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof ConnectScreen) {
            adoptInterceptedProxy();
        }
        if (event.getCurrentScreen() instanceof ConnectScreen && event.getNewScreen() != null) {
            releaseActiveProxyListener();
        }
    }

    private void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        synchronized (stateLock) {
            adoptInterceptedProxyLocked();
            if (activeProxy != null) {
                activeSession = activeProxy;
            }
        }
    }

    private void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        synchronized (stateLock) {
            closeActiveSessionLocked();
            remoteServerHudSnapshot = null;
            remoteServerHudSnapshotMillis = 0L;
        }
    }

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        boolean inSingleplayerWorld = minecraft.player != null && minecraft.getSingleplayerServer() != null;
        if (!inSingleplayerWorld) {
            singleplayerLanHintShown = false;
            return;
        }
        if (singleplayerLanHintShown) {
            return;
        }

        sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_hint"));
        sendClientMessage(Component.translatable("zstdnet.singleplayer.lan_command_hint"));
        singleplayerLanHintShown = true;
    }

    private void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        ServerProxyBootstrap.ServerHudSnapshot hostSnapshot = visibleHostSnapshot();
        boolean remoteServer = minecraft.getCurrentServer() != null;
        if (!remoteServer) {
            synchronized (stateLock) {
                closeActiveSessionLocked();
                closeActiveProxyLocked();
            }
        }

        LocalZstdNet.ProxyHandle session;
        synchronized (stateLock) {
            if (!hudVisible) {
                return;
            }
            session = remoteServer ? activeSession : null;
        }
        if (session == null && hostSnapshot == null) {
            return;
        }
        GuiGraphics gui = event.getGuiGraphics();
        int y = 8;

        if (hostSnapshot != null) {
            String hostMode = translateServerHudMode(hostSnapshot.mode());
            String[] hostLines = {
                I18n.get("zstdnet.hud.host.title", hostMode, hostSnapshot.listenHost(), hostSnapshot.listenPort()),
                I18n.get("zstdnet.hud.server.zstd_rate", formatRate(hostSnapshot.zstdDownRate()), formatRate(hostSnapshot.zstdUpRate())),
                I18n.get("zstdnet.hud.server.raw_rate", formatRate(hostSnapshot.rawDownRate()), formatRate(hostSnapshot.rawUpRate())),
                I18n.get("zstdnet.hud.server.zstd_total", formatSize(hostSnapshot.zstdBytes())),
                I18n.get("zstdnet.hud.server.raw_total", formatSize(hostSnapshot.rawBytes())),
                I18n.get("zstdnet.hud.server.ratio", formatPercent(hostSnapshot.ratioPercent())),
                I18n.get("zstdnet.hud.connections", hostSnapshot.connections())
            };
            y = renderHudPanel(gui, minecraft, y, hostLines, HUD_SERVER_BACKGROUND, HUD_SERVER_TITLE, HUD_SERVER_TEXT);
        }

        if (session != null) {
            LocalZstdNet.StatsSnapshot stats = session.statsSnapshot();
            String clientMode = translateClientHudMode(stats.mode());
            String[] clientLines = {
                I18n.get("zstdnet.hud.client.title", clientMode, stats.remoteHost(), stats.remotePort()),
                I18n.get("zstdnet.hud.client.wire", formatRate(stats.wireUpRate()), formatRate(stats.wireDownRate())),
                I18n.get("zstdnet.hud.client.raw", formatRate(stats.rawUpRate()), formatRate(stats.rawDownRate())),
                I18n.get(
                    "zstdnet.hud.total_ratio",
                    formatSize(stats.wireUpBytes() + stats.wireDownBytes()),
                    formatSize(stats.rawUpBytes() + stats.rawDownBytes()),
                    formatPercent(stats.ratioPercent())
                )
            };
            renderHudPanel(gui, minecraft, y, clientLines, HUD_CLIENT_BACKGROUND, HUD_CLIENT_TITLE, HUD_CLIENT_TEXT);
        }
    }

    private void onScreenRender(ScreenEvent.Render.Post event) {
        Screen shareToLanScreen = event.getScreen();
        if (!isShareToLanLikeScreen(shareToLanScreen)) {
            return;
        }

        ShareToLanState state = getShareToLanState(shareToLanScreen);
        if (state == null) {
            return;
        }

        syncShareToLanState(state);

        GuiGraphics gui = event.getGuiGraphics();
        int labelX = state.zstdPortEdit.getX();
        int labelY = state.zstdPortEdit.getY() - 10;
        gui.drawString(Minecraft.getInstance().font, ZSTD_PORT_LABEL, labelX, labelY, 0xFFFFFF);
    }

    private void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        Screen screen = event.getScreen();
        if (isShareToLanLikeScreen(screen)) {
            ensureShareToLanWidgetAttached(screen);
        }
    }

    private void ensureShareToLanWidgetAttached(Screen shareToLanScreen) {
        ShareToLanState state = getShareToLanState(shareToLanScreen);
        if (state != null) {
            ensureShareToLanWidgetAttached(shareToLanScreen, state);
        }
    }

    private void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("zstdhud")
                .executes(this::showHudStatus)
                .then(Commands.literal("on").executes(context -> setHudVisible(context, true)))
                .then(Commands.literal("off").executes(context -> setHudVisible(context, false)))
                .then(Commands.literal("toggle").executes(this::toggleHudVisible))
        );
        event.getDispatcher().register(buildPortCommand("zstdport"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildPortCommand(String literal) {
        return Commands.literal(literal)
            .executes(this::showPortStatus)
            .then(Commands.literal("show").executes(this::showPortStatus))
            .then(
                Commands.literal("zstd")
                    .requires(source -> canEditPortConfig())
                    .then(
                        Commands.argument("port", IntegerArgumentType.integer(MIN_PORT, MAX_PORT))
                            .executes(this::setZstdPort)
                    )
            )
            .then(
                Commands.literal("game")
                    .requires(source -> canEditPortConfig())
                    .then(
                        Commands.argument("port", IntegerArgumentType.integer(MIN_PORT, MAX_PORT))
                            .executes(this::setGamePort)
                    )
            )
            .then(buildVoiceTargetBranch("voice"))
            .then(buildVoiceListenBranch("zstdvoice"));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildVoiceTargetBranch(String literal) {
        return Commands.literal(literal)
            .requires(source -> canEditPortConfig())
            .executes(this::showVoicePortStatus)
            .then(Commands.argument("port", IntegerArgumentType.integer(MIN_PORT, MAX_PORT))
                .executes(this::setVoicePort));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildVoiceListenBranch(String literal) {
        return Commands.literal(literal)
            .requires(source -> canEditPortConfig())
            .executes(this::showVoicePortStatus)
            .then(Commands.argument("port", IntegerArgumentType.integer(MIN_PORT, MAX_PORT))
                .executes(this::setVoiceListenPort));
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
            return;
        }

        if (isShareToLanLikeScreen(screen)) {
            if (event.getKeyCode() != 257 && event.getKeyCode() != 335) {
                return;
            }
            ShareToLanState state = getShareToLanState(screen);
            if (state == null || !state.vanillaStartButton.active) {
                return;
            }
            if (prepareLanWorldPublish(state)) {
                state.vanillaStartButton.onPress();
            }
            event.setCanceled(true);
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
            return;
        }

        if (isShareToLanLikeScreen(screen)) {
            ShareToLanState state = getShareToLanState(screen);
            if (state == null || !state.vanillaStartButton.active) {
                return;
            }
            if (state.vanillaStartButton.isMouseOver(event.getMouseX(), event.getMouseY())) {
                if (!prepareLanWorldPublish(state)) {
                    event.setCanceled(true);
                }
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
            LOGGER.warn("zstdnet: invalid remote address {}", serverData.ip);
            return false;
        }

        RemoteTarget remote = resolveRemoteTarget(remoteAddr);
        if (remote == null) {
            LOGGER.warn("zstdnet: failed to resolve remote target {}", remoteAddr);
            return false;
        }
        LocalZstdNet.ProxyHandle proxy;

        try {
            synchronized (stateLock) {
                closeActiveProxyLocked();
                closeActiveSessionLocked();
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
            LOGGER.error("zstdnet: failed to start local proxy for {}", remoteAddr, e);
            return false;
        }

        serverData.ip = remoteAddr;
        String localAddr = "127.0.0.1:" + proxy.localPort();
        LOGGER.info("zstdnet: {} -> {} via local {}", safe(serverData.name), remoteAddr, localAddr);
        ConnectScreenHooks.setBypass(true);
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

    private ShareToLanState getShareToLanState(Screen screen) {
        synchronized (stateLock) {
            return shareToLanScreens.get(screen);
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

    private void adoptInterceptedProxy() {
        synchronized (stateLock) {
            adoptInterceptedProxyLocked();
        }
    }

    private void adoptInterceptedProxyLocked() {
        LocalZstdNet.ProxyHandle intercepted = ConnectScreenHooks.takeProxy();
        if (intercepted == null) {
            return;
        }

        closeActiveProxyLocked();
        closeActiveSessionLocked();
        activeProxy = intercepted;
        LOGGER.info("zstdnet: adopted intercepted programmatic proxy on local {}", intercepted.localPort());
    }

    private void releaseActiveProxyListener() {
        synchronized (stateLock) {
            if (activeProxy == null) {
                return;
            }
            try {
                LOGGER.info(
                    "zstdnet: releasing local proxy TCP listener on 127.0.0.1:{} while keeping active session resources",
                    activeProxy.localPort()
                );
                activeProxy.closeListener();
            } catch (Exception ignored) {
            }
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

    private void closeActiveSessionLocked() {
        if (activeSession == null) {
            return;
        }
        if (activeSession != activeProxy) {
            try {
                activeSession.close();
            } catch (Exception ignored) {
            }
        }
        activeSession = null;
    }

    private void shutdown() {
        synchronized (stateLock) {
            closeActiveProxyLocked();
            closeActiveSessionLocked();
        }
    }

    private int showHudStatus(CommandContext<CommandSourceStack> context) {
        boolean visible;
        synchronized (stateLock) {
            visible = hudVisible;
        }
        sendClientMessage(Component.translatable("zstdnet.command.hud.status", visible ? "ON" : "OFF"));
        return 1;
    }

    private int setHudVisible(CommandContext<CommandSourceStack> context, boolean visible) {
        synchronized (stateLock) {
            hudVisible = visible;
        }
        sendClientMessage(Component.translatable(visible ? "zstdnet.command.hud.enabled" : "zstdnet.command.hud.disabled"));
        return 1;
    }

    private int toggleHudVisible(CommandContext<CommandSourceStack> context) {
        boolean visible;
        synchronized (stateLock) {
            hudVisible = !hudVisible;
            visible = hudVisible;
        }
        sendClientMessage(Component.translatable(visible ? "zstdnet.command.hud.enabled" : "zstdnet.command.hud.disabled"));
        return 1;
    }

    private int showPortStatus(CommandContext<CommandSourceStack> context) {
        sendClientMessage(Component.translatable(
            "zstdnet.command.port.status",
            ServerProxyConfigFile.readListenPort(),
            ServerProxyConfigFile.readTargetPort()
        ));
        return 1;
    }

    private int showVoicePortStatus(CommandContext<CommandSourceStack> context) {
        sendClientMessage(Component.translatable(
            "zstdnet.command.port.voice_status",
            ServerProxyConfigFile.readVoiceListenPort(),
            ServerProxyConfigFile.readVoiceTargetPort()
        ));
        return 1;
    }

    private int setZstdPort(CommandContext<CommandSourceStack> context) {
        if (!canEditPortConfig()) {
            sendClientMessage(Component.translatable("zstdnet.command.port.no_permission"));
            return 0;
        }

        int port = IntegerArgumentType.getInteger(context, "port");
        int currentPort = ServerProxyConfigFile.readListenPort();
        if (port != currentPort && !HttpUtil.isPortAvailable(port)) {
            sendClientMessage(ZSTD_PORT_UNAVAILABLE);
            return 0;
        }
        try {
            ServerProxyConfigFile.writeListenPort(port);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to update zstd listen port {}", port, e);
            sendClientMessage(Component.translatable("zstdnet.command.port.write_failed"));
            return 0;
        }
        sendClientMessage(Component.translatable(
            isLanPublished() ? "zstdnet.command.port.zstd_reloaded" : "zstdnet.command.port.zstd_set",
            port
        ));
        return 1;
    }

    private int setGamePort(CommandContext<CommandSourceStack> context) {
        if (!canEditPortConfig()) {
            sendClientMessage(Component.translatable("zstdnet.command.port.no_permission"));
            return 0;
        }

        int port = IntegerArgumentType.getInteger(context, "port");
        try {
            ServerProxyConfigFile.writeTargetPort(port);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to update game target port {}", port, e);
            sendClientMessage(Component.translatable("zstdnet.command.port.write_failed"));
            return 0;
        }
        sendClientMessage(Component.translatable(
            isLanPublished() ? "zstdnet.command.port.game_set_reopen" : "zstdnet.command.port.game_set",
            port
        ));
        return 1;
    }

    private int setVoicePort(CommandContext<CommandSourceStack> context) {
        if (!canEditPortConfig()) {
            sendClientMessage(Component.translatable("zstdnet.command.voice.no_permission"));
            return 0;
        }

        int port = IntegerArgumentType.getInteger(context, "port");
        try {
            ServerProxyConfigFile.writeVoiceTargetPort(port);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to update voice target port {}", port, e);
            sendClientMessage(Component.translatable("zstdnet.command.voice.write_failed"));
            return 0;
        }
        sendClientMessage(Component.translatable(
            "zstdnet.command.port.voice_target_set",
            ServerProxyConfigFile.readVoiceTargetPort()
        ));
        return 1;
    }

    private int setVoiceListenPort(CommandContext<CommandSourceStack> context) {
        if (!canEditPortConfig()) {
            sendClientMessage(Component.translatable("zstdnet.command.voice.no_permission"));
            return 0;
        }

        int port = IntegerArgumentType.getInteger(context, "port");
        try {
            ServerProxyConfigFile.writeVoiceListenPort(port);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to update voice listen port {}", port, e);
            sendClientMessage(Component.translatable("zstdnet.command.voice.write_failed"));
            return 0;
        }
        sendClientMessage(Component.translatable(
            "zstdnet.command.port.voice_listen_set",
            ServerProxyConfigFile.readVoiceListenPort()
        ));
        return 1;
    }

    private boolean canEditPortConfig() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null
            && minecraft.getSingleplayerServer() != null
            && minecraft.player.hasPermissions(2);
    }

    private boolean isLanPublished() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getSingleplayerServer() != null && minecraft.getSingleplayerServer().isPublished();
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

    private static boolean isLanStartButton(Button button) {
        return button != null && Objects.equals(button.getMessage().getString(), I18n.get("lanServer.start"));
    }

    private static boolean isLanPortEdit(EditBox editBox) {
        return editBox != null && Objects.equals(editBox.getMessage().getString(), I18n.get("lanServer.port"));
    }

    private static boolean isShareToLanLikeScreen(Screen screen) {
        if (screen instanceof ShareToLanScreen) {
            return true;
        }
        return screen != null && screen.getClass().getName().toLowerCase(Locale.ROOT).contains("sharetolan");
    }

    private static boolean looksLikeVanillaLanPortEdit(EditBox editBox, Screen screen) {
        if (editBox == null) {
            return false;
        }
        return editBox.getWidth() == 150
            && editBox.getHeight() == 20
            && editBox.getY() == 160
            && Math.abs(editBox.getX() - (screen.width / 2 - 75)) <= 4;
    }

    private static EditBox findBackendPortEdit(List<?> listeners, Screen screen) {
        EditBox exact = null;
        EditBox fallback = null;

        for (Object listener : listeners) {
            if (!(listener instanceof EditBox editBox)) {
                continue;
            }
            if (isLanPortEdit(editBox)) {
                return editBox;
            }
            if (looksLikeVanillaLanPortEdit(editBox, screen)) {
                exact = editBox;
            }
            if (fallback == null || editBox.getY() < fallback.getY() || (editBox.getY() == fallback.getY() && editBox.getX() < fallback.getX())) {
                fallback = editBox;
            }
        }

        return exact != null ? exact : fallback;
    }

    private static int findLowestEditBoxBottom(List<?> listeners) {
        int bottom = Integer.MIN_VALUE;
        for (Object listener : listeners) {
            if (listener instanceof EditBox editBox) {
                bottom = Math.max(bottom, editBox.getY() + editBox.getHeight());
            }
        }
        return bottom;
    }

    private static Integer tryReadPort(EditBox editBox) {
        if (editBox == null) {
            return null;
        }
        String raw = editBox.getValue();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port >= MIN_PORT && port <= MAX_PORT ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unnamed" : value.trim();
    }

    private static void sendClientMessage(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(message);
        } else {
            LOGGER.info(message.getString());
        }
    }

    private void updateRemoteServerHudSnapshot(ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        synchronized (stateLock) {
            remoteServerHudSnapshot = snapshot;
            remoteServerHudSnapshotMillis = System.currentTimeMillis();
        }
    }

    private ServerProxyBootstrap.ServerHudSnapshot visibleHostSnapshot() {
        ServerProxyBootstrap.ServerHudSnapshot localSnapshot = ServerProxyBootstrap.currentHudSnapshot();
        if (localSnapshot != null) {
            return localSnapshot;
        }

        synchronized (stateLock) {
            if (remoteServerHudSnapshot == null) {
                return null;
            }
            if (System.currentTimeMillis() - remoteServerHudSnapshotMillis > REMOTE_HUD_SNAPSHOT_TTL_MS) {
                remoteServerHudSnapshot = null;
                remoteServerHudSnapshotMillis = 0L;
                return null;
            }
            return remoteServerHudSnapshot;
        }
    }

    private static String formatRate(long bytesPerSecond) {
        return formatSize(bytesPerSecond) + "/s";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }

        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0D;
        int unit = 0;
        while (value >= 1024.0D && unit < units.length - 1) {
            value /= 1024.0D;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private static int renderHudPanel(
        GuiGraphics gui,
        Minecraft minecraft,
        int startY,
        String[] lines,
        int backgroundColor,
        int titleColor,
        int textColor
    ) {
        int x = 8;
        int y = startY;
        int lineHeight = 10;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, minecraft.font.width(line));
        }
        width += 8;
        int height = lineHeight * lines.length + 6;

        gui.fill(x - 3, y - 3, x - 3 + width, y - 3 + height, backgroundColor);
        for (int i = 0; i < lines.length; i++) {
            int color = i == 0 ? titleColor : textColor;
            gui.drawString(minecraft.font, lines[i], x, y + lineHeight * i, color);
        }
        return y + height + 4;
    }

    private static String translateServerHudMode(String mode) {
        if (mode == null) {
            return "";
        }
        return switch (mode) {
            case "DEDICATED" -> I18n.get("zstdnet.hud.mode.dedicated");
            case "LAN" -> I18n.get("zstdnet.hud.mode.lan");
            default -> mode;
        };
    }

    private static String translateClientHudMode(LocalZstdNet.Mode mode) {
        if (mode == null) {
            return "";
        }
        return switch (mode) {
            case AUTO -> I18n.get("zstdnet.hud.mode.auto");
            case RAW -> I18n.get("zstdnet.hud.mode.raw");
            case ZSTD -> I18n.get("zstdnet.hud.mode.zstd");
        };
    }

    private static String formatPercent(double value) {
        return String.format("%.2f%%", value);
    }

    private ShareToLanState attachShareToLanState(Screen screen, ScreenEvent.Init.Post event, List<?> listeners) {
        ShareToLanState existing = getShareToLanState(screen);
        if (existing != null) {
            event.removeListener(existing.zstdPortEdit);
        }

        EditBox backendPortEdit = findBackendPortEdit(listeners, screen);
        applyDefaultBackendPort(backendPortEdit);
        Button vanillaStartButton = null;
        for (Object listener : listeners) {
            if (listener instanceof Button button && isLanStartButton(button)) {
                vanillaStartButton = button;
            }
        }

        if (vanillaStartButton == null) {
            return null;
        }

        int preferredZstdPort = ServerProxyConfigFile.readListenPort();
        int defaultBackendPort = resolveBackendPortForLan(backendPortEdit, preferredZstdPort);
        int defaultZstdPort = findAvailableZstdPort(preferredZstdPort, defaultBackendPort);
        int zstdFieldWidth = 150;
        int zstdFieldHeight = 20;
        int zstdFieldX = screen.width / 2 - zstdFieldWidth / 2;
        int zstdFieldY = vanillaStartButton.getY() - 28;
        if (backendPortEdit != null) {
            zstdFieldWidth = backendPortEdit.getWidth();
            zstdFieldHeight = backendPortEdit.getHeight();
            zstdFieldX = Math.max(8, backendPortEdit.getX() - zstdFieldWidth - 8);
            zstdFieldY = backendPortEdit.getY();
        } else {
            int lowestEditBottom = findLowestEditBoxBottom(listeners);
            if (lowestEditBottom != Integer.MIN_VALUE) {
                zstdFieldY = Math.min(lowestEditBottom + 28, vanillaStartButton.getY() - 28);
            }
        }
        EditBox zstdPortEdit = new EditBox(
            screen.getMinecraft().font,
            zstdFieldX,
            zstdFieldY,
            zstdFieldWidth,
            zstdFieldHeight,
            ZSTD_PORT_LABEL
        );
        zstdPortEdit.setMaxLength(5);
        zstdPortEdit.setValue(String.valueOf(defaultZstdPort));
        zstdPortEdit.setHint(Component.translatable("zstdnet.share_to_lan.zstd_port_auto", defaultZstdPort).withStyle(ChatFormatting.DARK_GRAY));
        zstdPortEdit.setTooltip(Tooltip.create(ZSTD_PORT_HELP));
        zstdPortEdit.setFocused(false);

        ShareToLanState state = new ShareToLanState(backendPortEdit, vanillaStartButton, zstdPortEdit, defaultZstdPort);
        zstdPortEdit.setResponder(raw -> applyZstdPortResponse(state, raw));
        applyZstdPortResponse(state, zstdPortEdit.getValue());

        event.addListener(zstdPortEdit);
        return state;
    }

    private void applyDefaultBackendPort(EditBox backendPortEdit) {
        if (backendPortEdit == null) {
            return;
        }
        String current = backendPortEdit.getValue();
        if (current != null && !current.isBlank()) {
            return;
        }
        backendPortEdit.setHint(Component.translatable("zstdnet.share_to_lan.backend_port_auto").withStyle(ChatFormatting.DARK_GRAY));
    }

    private void applyZstdPortResponse(ShareToLanState state, String raw) {
        PortValidation validation = validateZstdPort(raw, state.defaultZstdPort, state.backendPortEdit);
        state.zstdPort = validation.port();
        state.zstdError = validation.error();
        state.zstdPortEdit.setTextColor(validation.error() == null ? PORT_TEXT_NORMAL : PORT_TEXT_INVALID);
        state.zstdPortEdit.setTooltip(Tooltip.create(validation.error() == null ? ZSTD_PORT_HELP : validation.error()));
        syncShareToLanState(state);
    }

    private void syncShareToLanState(ShareToLanState state) {
        state.zstdPortEdit.setTooltip(Tooltip.create(state.zstdError == null ? ZSTD_PORT_HELP : state.zstdError));
    }

    private static int findAvailableZstdPort(int preferredPort, Integer reservedPort) {
        int startPort = clampPort(preferredPort);
        for (int port = startPort; port <= MAX_PORT; port++) {
            if (isZstdPortAvailable(port, reservedPort)) {
                return port;
            }
        }
        for (int port = MIN_PORT; port < startPort; port++) {
            if (isZstdPortAvailable(port, reservedPort)) {
                return port;
            }
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            return isReservedZstdPort(port, reservedPort) ? startPort : port;
        } catch (IOException ignored) {
            return startPort;
        }
    }

    private static boolean isZstdPortAvailable(int port, Integer reservedPort) {
        return !isReservedZstdPort(port, reservedPort) && HttpUtil.isPortAvailable(port);
    }

    private static boolean isReservedZstdPort(int port, Integer reservedPort) {
        return Objects.equals(port, reservedPort);
    }

    private static int resolveBackendPortForLan(EditBox backendPortEdit, int zstdPort) {
        Integer backendPort = tryReadPort(backendPortEdit);
        if (backendPort != null) {
            return backendPort;
        }
        return findAvailableBackendPort(ServerProxyConfigFile.readTargetPort(), zstdPort);
    }

    private static int findAvailableBackendPort(int preferredPort, int reservedPort) {
        int startPort = clampPort(preferredPort);
        if (isBackendPortAvailable(startPort, reservedPort)) {
            return startPort;
        }
        int defaultPort = clampPort(DEFAULT_BACKEND_PORT);
        if (isBackendPortAvailable(defaultPort, reservedPort)) {
            return defaultPort;
        }
        for (int port = Math.max(MIN_PORT, startPort + 1); port <= MAX_PORT; port++) {
            if (isBackendPortAvailable(port, reservedPort)) {
                return port;
            }
        }
        for (int port = MIN_PORT; port < startPort; port++) {
            if (isBackendPortAvailable(port, reservedPort)) {
                return port;
            }
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            return port == reservedPort ? startPort : port;
        } catch (IOException ignored) {
            return startPort;
        }
    }

    private static boolean isBackendPortAvailable(int port, int reservedPort) {
        return port != reservedPort && HttpUtil.isPortAvailable(port);
    }

    private static int clampPort(int port) {
        return Math.max(MIN_PORT, Math.min(MAX_PORT, port));
    }

    private static void ensureShareToLanWidgetAttached(Screen screen, ShareToLanState state) {
        if (!reattachWidgetToScreenLists(screen, state.zstdPortEdit)) {
            LOGGER.debug("zstdnet: failed to reattach share-to-lan zstd port field");
        }
    }

    private static boolean reattachWidgetToScreenLists(Screen screen, EditBox widget) {
        boolean attached = false;
        for (Class<?> type = Screen.class; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Collection.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value instanceof List<?> list) {
                        attached |= addWidgetToRawList(list, widget);
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                    LOGGER.debug("zstdnet: failed to inspect screen widget list for share-to-lan field", e);
                }
            }
        }
        return screen.children().contains(widget);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean addWidgetToRawList(List list, EditBox widget) {
        if (list.contains(widget)) {
            return false;
        }
        list.add(widget);
        return true;
    }

    private PortValidation validateZstdPort(String raw, int fallbackPort, EditBox backendPortEdit) {
        String text = raw == null ? "" : raw.trim();
        int port = fallbackPort;

        if (!text.isEmpty()) {
            try {
                port = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return new PortValidation(fallbackPort, ZSTD_PORT_INVALID);
            }
        }

        if (port < MIN_PORT || port > MAX_PORT) {
            return new PortValidation(fallbackPort, ZSTD_PORT_INVALID);
        }
        if (!isZstdPortAvailable(port, resolveBackendPortForLan(backendPortEdit, port))) {
            return new PortValidation(port, ZSTD_PORT_UNAVAILABLE);
        }
        return new PortValidation(port, null);
    }

    private boolean prepareLanWorldPublish(ShareToLanState state) {
        syncShareToLanState(state);
        applyZstdPortResponse(state, state.zstdPortEdit.getValue());
        if (state.zstdError != null) {
            return false;
        }
        int backendPort = resolveBackendPortForLan(state.backendPortEdit, state.zstdPort);
        if (state.backendPortEdit != null && (state.backendPortEdit.getValue() == null || state.backendPortEdit.getValue().isBlank())) {
            state.backendPortEdit.setValue(String.valueOf(backendPort));
        }
        try {
            ServerProxyConfigFile.writePorts(state.zstdPort, backendPort);
        } catch (IOException e) {
            LOGGER.error("zstdnet: failed to write LAN zstd port {}", state.zstdPort, e);
            sendClientMessage(Component.translatable("zstdnet.share_to_lan.write_failed"));
            return false;
        }
        return true;
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

    private static final class ShareToLanState {
        private final EditBox backendPortEdit;
        private final Button vanillaStartButton;
        private final EditBox zstdPortEdit;
        private final int defaultZstdPort;
        private Component zstdError;
        private int zstdPort;

        private ShareToLanState(
            EditBox backendPortEdit,
            Button vanillaStartButton,
            EditBox zstdPortEdit,
            int defaultZstdPort
        ) {
            this.backendPortEdit = backendPortEdit;
            this.vanillaStartButton = vanillaStartButton;
            this.zstdPortEdit = zstdPortEdit;
            this.defaultZstdPort = defaultZstdPort;
            this.zstdPort = defaultZstdPort;
        }
    }

    private record PortValidation(int port, Component error) {
    }

    private record RemoteTarget(String connectHost, int connectPort, String presentedHost, int presentedPort) {
    }
}
