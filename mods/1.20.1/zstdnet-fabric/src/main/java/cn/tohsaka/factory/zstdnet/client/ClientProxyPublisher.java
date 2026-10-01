package cn.tohsaka.factory.zstdnet.client;

import cn.tohsaka.factory.zstdnet.network.LanCompressionSync;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class ClientProxyPublisher {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SNAPSHOT_TTL_MS = 3000L;
    private static volatile ServerProxyBootstrap.ServerHudSnapshot remoteSnapshot;
    private static volatile long remoteSnapshotAt;
    private static boolean hudVisible;
    private static boolean lanHintShown;

    private ClientProxyPublisher() {
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            remoteSnapshot = null;
            remoteSnapshotAt = 0L;
        });
        ClientTickEvents.END_CLIENT_TICK.register(ClientProxyPublisher::onClientTick);
        HudRenderCallback.EVENT.register((gui, tickCounter) -> renderHud(gui));
    }

    public static void acceptRemoteServerHudSnapshot(ServerProxyBootstrap.ServerHudSnapshot snapshot) {
        remoteSnapshot = snapshot;
        remoteSnapshotAt = System.currentTimeMillis();
    }

    public static void acceptTrafficReportResponse(boolean success, String payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!success) {
            sendMessage(Component.literal("[ZstdNet] " + payload).withStyle(ChatFormatting.RED));
            return;
        }
        Path reportsDir = minecraft.gameDirectory.toPath().resolve("config").resolve("zstdnet").resolve("reports");
        String serverIdentity = minecraft.getCurrentServer() == null ? "singleplayer" : minecraft.getCurrentServer().ip;
        CompletableFuture.supplyAsync(() -> {
            try {
                return TrafficReportGenerator.generate(reportsDir, serverIdentity, payload);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).whenComplete((generated, error) -> minecraft.execute(() -> {
            if (error != null) {
                LOGGER.error("[zstdnet-client] failed to generate traffic report", error);
                sendMessage(Component.literal("[ZstdNet] 流量报告生成失败。").withStyle(ChatFormatting.RED));
                return;
            }
            String path = generated.latest().toString();
            Component open = Component.literal("[打开统计面板]").withStyle(style -> style
                .withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(path))));
            sendMessage(Component.literal("[ZstdNet] 报告已保存 ").withStyle(ChatFormatting.GRAY).append(open));
            sendMessage(Component.literal(generated.archive().toString()).withStyle(ChatFormatting.DARK_GRAY));
        }));
    }

    private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal("zstdhud")
            .executes(context -> showHudStatus())
            .then(ClientCommandManager.literal("on").executes(context -> setHud(true)))
            .then(ClientCommandManager.literal("off").executes(context -> setHud(false)))
            .then(ClientCommandManager.literal("toggle").executes(context -> setHud(!hudVisible))));
        LiteralArgumentBuilder<FabricClientCommandSource> report = ClientCommandManager.literal("zstdreport")
            .executes(context -> requestReport("today"));
        for (String range : List.of("today", "session", "24h", "7d", "30d")) {
            report.then(ClientCommandManager.literal(range).executes(context -> requestReport(range)));
        }
        dispatcher.register(report);
    }

    private static int showHudStatus() {
        sendMessage(Component.translatable("zstdnet.command.hud.status", hudVisible ? "ON" : "OFF"));
        return 1;
    }

    private static int setHud(boolean visible) {
        hudVisible = visible;
        sendMessage(Component.translatable(visible ? "zstdnet.command.hud.enabled" : "zstdnet.command.hud.disabled"));
        return 1;
    }

    private static int requestReport(String range) {
        if (Minecraft.getInstance().getConnection() == null) {
            sendMessage(Component.literal("[ZstdNet] 请先进入安装了 ZstdNet 的服务器。").withStyle(ChatFormatting.RED));
            return 0;
        }
        LanCompressionSync.requestTrafficReport(range);
        sendMessage(Component.literal("[ZstdNet] 正在请求 " + range + " 流量报告…").withStyle(ChatFormatting.GRAY));
        return 1;
    }

    private static void onClientTick(Minecraft minecraft) {
        boolean singleplayer = minecraft.player != null && minecraft.getSingleplayerServer() != null;
        if (!singleplayer) {
            lanHintShown = false;
        } else if (!lanHintShown) {
            sendMessage(Component.translatable("zstdnet.singleplayer.integrated_hint"));
            lanHintShown = true;
        }
    }

    private static void renderHud(GuiGraphics gui) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!hudVisible || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        ServerProxyBootstrap.ServerHudSnapshot snapshot = ServerProxyBootstrap.currentHudSnapshot();
        if (snapshot == null && remoteSnapshotAt > 0L
                && System.currentTimeMillis() - remoteSnapshotAt <= SNAPSHOT_TTL_MS) {
            snapshot = remoteSnapshot;
        }
        if (snapshot == null) {
            return;
        }
        String[] lines = {
            I18n.get("zstdnet.hud.integrated.title", snapshot.listenPort()),
            I18n.get("zstdnet.hud.server.zstd_rate", rate(snapshot.zstdUpRate()), rate(snapshot.zstdDownRate())),
            I18n.get("zstdnet.hud.server.raw_rate", rate(snapshot.rawUpRate()), rate(snapshot.rawDownRate())),
            I18n.get("zstdnet.hud.server.zstd_total", size(snapshot.zstdBytes())),
            I18n.get("zstdnet.hud.server.raw_total", size(snapshot.rawBytes())),
            I18n.get("zstdnet.hud.server.ratio", String.format("%.2f%%", snapshot.ratioPercent())),
            I18n.get("zstdnet.hud.connections", snapshot.connections())
        };
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, minecraft.font.width(line));
        }
        gui.fill(5, 5, 13 + width, 8 + lines.length * 10, 0x90241208);
        for (int i = 0; i < lines.length; i++) {
            gui.drawString(minecraft.font, lines[i], 8, 8 + i * 10, i == 0 ? 0xFFE1A3 : 0xFFD08A);
        }
    }

    private static String rate(long bytes) {
        return size(bytes) + "/s";
    }

    private static String size(long bytes) {
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

    private static void sendMessage(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(message);
        } else {
            LOGGER.info(message.getString());
        }
    }
}
