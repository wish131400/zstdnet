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

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

public final class ServerProxyConfigFile {
    private static final int DEFAULT_MINECRAFT_PORT = 25565;
    private static final int DEFAULT_ZSTD_PORT = 25565;
    private static final int DEFAULT_BACKEND_PORT = 25566;
    private static final int DEFAULT_VOICE_CHAT_PORT = 24454;
    private static final int DEFAULT_VOICE_CHAT_LISTEN_PORT = 24455;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";
    private static final String DEFAULT_TARGET_HOST = "127.0.0.1";
    private static final String DEFAULT_VOICE_CHAT_LISTEN = DEFAULT_LISTEN_HOST + ":" + DEFAULT_VOICE_CHAT_LISTEN_PORT;
    private static final String DEFAULT_TRUSTED_PROXY_IPS = "127.0.0.1,::1,0:0:0:0:0:0:0:1";
    private static final Set<String> KNOWN_KEYS = Set.of(
        "enabled",
        "auto_takeover",
        "listen",
        "target",
        "voice_chat_passthrough",
        "voice_chat_listen",
        "voice_chat_target",
        "level",
        "max_conn_per_ip",
        "max_req_per_window",
        "request_window",
        "ban_duration",
        "stats_interval",
        "flush_interval",
        "idle_timeout",
        "max_rate_per_conn_bps",
        "max_rate_global_bps",
        "burst_bytes",
        "trust_proxy_protocol",
        "trusted_proxy_ips"
    );

    private ServerProxyConfigFile() {
    }

    public static Path path() {
        return FMLPaths.GAMEDIR.get().resolve("config").resolve("zstdnet-server.properties");
    }

    public static int readListenPort() {
        return parsePort(loadProperties().getProperty("listen", "0.0.0.0:" + DEFAULT_ZSTD_PORT), DEFAULT_ZSTD_PORT);
    }

    public static int readTargetPort() {
        return parsePort(loadProperties().getProperty("target", "127.0.0.1:" + DEFAULT_BACKEND_PORT), DEFAULT_BACKEND_PORT);
    }

    public static int readVoiceListenPort() {
        return parsePort(loadProperties().getProperty("voice_chat_listen", DEFAULT_VOICE_CHAT_LISTEN), DEFAULT_VOICE_CHAT_LISTEN_PORT);
    }

    public static int readVoiceTargetPort() {
        return parsePort(loadProperties().getProperty("voice_chat_target", defaultVoiceChatTarget()), defaultVoiceChatTargetPort());
    }

    public static void writeListenPort(int port) throws IOException {
        writePorts(port, null);
    }

    public static void writeTargetPort(int port) throws IOException {
        writePorts(null, port);
    }

    public static void writeVoiceTargetPort(int port) throws IOException {
        writeVoicePorts(null, port);
    }

    public static void writeVoiceListenPort(int port) throws IOException {
        writeVoicePorts(port, null);
    }

    public static void writePorts(Integer listenPort, Integer targetPort) throws IOException {
        Path path = path();
        Properties props = normalizeProperties(loadProperties());
        Files.createDirectories(path.getParent());

        String currentListen = props.getProperty("listen", DEFAULT_LISTEN_HOST + ":" + DEFAULT_ZSTD_PORT);
        String currentTarget = props.getProperty("target", DEFAULT_TARGET_HOST + ":" + DEFAULT_BACKEND_PORT);
        String listenHost = parseHost(currentListen, DEFAULT_LISTEN_HOST);
        String targetHost = parseHost(currentTarget, DEFAULT_TARGET_HOST);
        String listenValue = listenHost + ":" + (listenPort != null ? listenPort : parsePort(currentListen, DEFAULT_ZSTD_PORT));
        String targetValue = targetHost + ":" + (targetPort != null ? targetPort : parsePort(currentTarget, DEFAULT_BACKEND_PORT));
        props.setProperty("enabled", "true");
        props.putIfAbsent("auto_takeover", "false");
        props.setProperty("listen", listenValue);
        props.setProperty("target", targetValue);
        writeConfigWithComments(path, props, detectLineSeparator(path));
    }

    public static void writeVoicePorts(Integer listenPort, Integer targetPort) throws IOException {
        Path path = path();
        Properties props = normalizeProperties(loadProperties());
        Files.createDirectories(path.getParent());

        String currentListen = props.getProperty("voice_chat_listen", DEFAULT_VOICE_CHAT_LISTEN);
        String currentTarget = props.getProperty("voice_chat_target", defaultVoiceChatTarget());
        String listenHost = parseHost(currentListen, DEFAULT_LISTEN_HOST);
        String targetHost = parseHost(currentTarget, DEFAULT_TARGET_HOST);
        int resolvedTargetPort = targetPort != null ? targetPort : parsePort(currentTarget, DEFAULT_VOICE_CHAT_PORT);
        int resolvedListenPort = listenPort != null ? listenPort : parsePort(currentListen, DEFAULT_VOICE_CHAT_LISTEN_PORT);
        String listenValue = listenHost + ":" + resolvedListenPort;
        String targetValue = targetHost + ":" + resolvedTargetPort;
        props.setProperty("voice_chat_passthrough", "true");
        props.setProperty("voice_chat_listen", listenValue);
        props.setProperty("voice_chat_target", targetValue);
        writeConfigWithComments(path, props, detectLineSeparator(path));
    }

    public static void writeResolvedAutoTakeoverConfig(
        String listenHost,
        int listenPort,
        int targetPort
    ) throws IOException {
        Path path = path();
        Properties props = normalizeProperties(loadProperties());
        Files.createDirectories(path.getParent());

        props.setProperty("enabled", "true");
        props.setProperty("auto_takeover", "true");
        props.setProperty("listen", parseHost(listenHost, DEFAULT_LISTEN_HOST) + ":" + listenPort);
        props.setProperty("target", DEFAULT_TARGET_HOST + ":" + targetPort);
        writeConfigWithComments(path, props, detectLineSeparator(path));
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        Path path = path();
        if (!Files.exists(path)) {
            props.setProperty("enabled", "true");
            props.setProperty("auto_takeover", "true");
            props.setProperty("listen", "0.0.0.0:" + DEFAULT_ZSTD_PORT);
            props.setProperty("target", "127.0.0.1:" + DEFAULT_BACKEND_PORT);
            props.setProperty("voice_chat_passthrough", "true");
            props.setProperty("voice_chat_listen", DEFAULT_VOICE_CHAT_LISTEN);
            props.setProperty("voice_chat_target", defaultVoiceChatTarget());
            return props;
        }

        try {
            String text = stripUtf8Bom(Files.readString(path, StandardCharsets.UTF_8));
            try (Reader reader = new StringReader(text)) {
                props.load(reader);
            }
        } catch (IOException ignored) {
        }
        return props;
    }

    private static Properties normalizeProperties(Properties rawProps) {
        Properties props = new Properties();
        if (rawProps != null) {
            props.putAll(rawProps);
        }

        props.remove("allow_raw_login");
        props.putIfAbsent("enabled", "true");
        props.putIfAbsent("auto_takeover", "true");
        props.putIfAbsent("listen", DEFAULT_LISTEN_HOST + ":" + DEFAULT_ZSTD_PORT);
        props.putIfAbsent("target", DEFAULT_TARGET_HOST + ":" + DEFAULT_BACKEND_PORT);
        props.putIfAbsent("voice_chat_passthrough", "true");
        props.putIfAbsent("voice_chat_listen", DEFAULT_VOICE_CHAT_LISTEN);
        props.putIfAbsent("voice_chat_target", defaultVoiceChatTarget());
        props.putIfAbsent("level", "9");
        props.putIfAbsent("max_conn_per_ip", "9999");
        props.putIfAbsent("max_req_per_window", "50");
        props.putIfAbsent("request_window", "10s");
        props.putIfAbsent("ban_duration", "1m");
        props.putIfAbsent("stats_interval", "0s");
        props.putIfAbsent("flush_interval", "2ms");
        props.putIfAbsent("idle_timeout", "0");
        props.putIfAbsent("max_rate_per_conn_bps", "0");
        props.putIfAbsent("max_rate_global_bps", "0");
        props.putIfAbsent("burst_bytes", "262144");
        props.putIfAbsent("trust_proxy_protocol", "false");
        props.putIfAbsent("trusted_proxy_ips", DEFAULT_TRUSTED_PROXY_IPS);
        return props;
    }

    private static String stripUtf8Bom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static boolean isCommentFragmentKey(String key, String value) {
        String normalizedKey = key.stripLeading();
        if (normalizedKey.startsWith("#")
            || normalizedKey.startsWith("!")
            || normalizedKey.startsWith("\u00EF\u00BB\u00BF#")
            || normalizedKey.startsWith("\u00EF\u00BB\u00BF!")) {
            return true;
        }

        String normalizedValue = value == null ? "" : value.stripLeading();
        return normalizedKey.endsWith("#") && normalizedValue.startsWith("---");
    }

    private static String detectLineSeparator(Path path) throws IOException {
        if (!Files.exists(path)) {
            return System.lineSeparator();
        }
        String text = Files.readString(path, StandardCharsets.UTF_8);
        return text.contains("\r\n") ? "\r\n" : "\n";
    }

    private static int defaultVoiceChatTargetPort() {
        return FMLEnvironment.dist == Dist.CLIENT ? DEFAULT_MINECRAFT_PORT : DEFAULT_VOICE_CHAT_PORT;
    }

    private static String defaultVoiceChatTarget() {
        return DEFAULT_TARGET_HOST + ":" + defaultVoiceChatTargetPort();
    }

    private static void writeConfigWithComments(Path path, Properties rawProps, String lineSeparator) throws IOException {
        Properties props = normalizeProperties(rawProps);
        StringBuilder builder = new StringBuilder(1024);
        appendLine(builder, "# ------------------------------------------------------------", lineSeparator);
        appendLine(builder, "# zstdnet 内置服务端配置（自动维护）", lineSeparator);
        appendLine(builder, "# 命令改端口和自动接管时，都会按这份带注释模板重新写回。", lineSeparator);
        appendLine(builder, "# 这样即使配置被更新，说明文字也会保留。", lineSeparator);
        appendLine(builder, "# ------------------------------------------------------------", lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 是否启用内置 zstd 代理。", lineSeparator);
        appendLine(builder, "enabled=" + props.getProperty("enabled"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 是否自动接管当前对外公开的游戏端口。", lineSeparator);
        appendLine(builder, "# 专用服开启后，会把 server.properties 的 server-port 作为公网入口，", lineSeparator);
        appendLine(builder, "# 再把真正的后端游戏端口自动挪到别的本地端口。", lineSeparator);
        appendLine(builder, "auto_takeover=" + props.getProperty("auto_takeover"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# zstd 公网监听入口。示例：0.0.0.0:25565", lineSeparator);
        appendLine(builder, "listen=" + props.getProperty("listen"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 后端 Minecraft 目标地址。示例：127.0.0.1:25566", lineSeparator);
        appendLine(builder, "target=" + props.getProperty("target"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 是否透传 Simple Voice Chat 的 UDP。", lineSeparator);
        appendLine(builder, "voice_chat_passthrough=" + props.getProperty("voice_chat_passthrough"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 语音聊天的公网 UDP 入口。留空时，单机 / LAN 会跟随当前开放端口；写了值就按配置走。", lineSeparator);
        appendLine(builder, "# /zstdport zstdvoice 会修改这个值。", lineSeparator);
        appendLine(builder, "voice_chat_listen=" + props.getProperty("voice_chat_listen"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 语音聊天的后端 UDP 目标。留空时，单机 / LAN 会指向当前 LAN 端口；写了值就按配置走。", lineSeparator);
        appendLine(builder, "# 专用服留空时默认指向本机 24454。", lineSeparator);
        appendLine(builder, "# /zstdport voice 会修改这个值。", lineSeparator);
        appendLine(builder, "voice_chat_target=" + props.getProperty("voice_chat_target"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# zstd 压缩等级（1-22，通常建议 3-9）。", lineSeparator);
        appendLine(builder, "level=" + props.getProperty("level"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 单个 IP 的最大并发连接数。0 表示不限制。", lineSeparator);
        appendLine(builder, "max_conn_per_ip=" + props.getProperty("max_conn_per_ip"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 单个 IP 在 request_window 内允许的最大请求次数。<=0 表示不限制。", lineSeparator);
        appendLine(builder, "max_req_per_window=" + props.getProperty("max_req_per_window"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 请求计数时间窗口。", lineSeparator);
        appendLine(builder, "request_window=" + props.getProperty("request_window"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 超限后的封禁时长。", lineSeparator);
        appendLine(builder, "ban_duration=" + props.getProperty("ban_duration"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 运行时统计日志输出间隔。0 表示关闭周期统计。", lineSeparator);
        appendLine(builder, "stats_interval=" + props.getProperty("stats_interval"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# zstd flush 间隔。0 或 0ms 表示每次写入都 flush。", lineSeparator);
        appendLine(builder, "flush_interval=" + props.getProperty("flush_interval"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 后端空闲超时。0 表示关闭超时。", lineSeparator);
        appendLine(builder, "idle_timeout=" + props.getProperty("idle_timeout"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 单连接带宽限制，单位字节/秒。0 表示不限制。", lineSeparator);
        appendLine(builder, "max_rate_per_conn_bps=" + props.getProperty("max_rate_per_conn_bps"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 全局总带宽限制，单位字节/秒。0 表示不限制。", lineSeparator);
        appendLine(builder, "max_rate_global_bps=" + props.getProperty("max_rate_global_bps"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 令牌桶突发容量，单位字节。", lineSeparator);
        appendLine(builder, "burst_bytes=" + props.getProperty("burst_bytes"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 如果玩家是通过 frp / 反代进服，并且你想让后端看到玩家真实 IP，就改成 true。", lineSeparator);
        appendLine(builder, "# 普通直连、本机测试、局域网、公网直连都保持 false。", lineSeparator);
        appendLine(builder, "# 改成 true 后，直接连 zstdnet 入口端口但不带 PROXY v2 头的连接会被拒绝。", lineSeparator);
        appendLine(builder, "trust_proxy_protocol=" + props.getProperty("trust_proxy_protocol"), lineSeparator);
        appendLine(builder, "", lineSeparator);

        appendLine(builder, "# 允许哪些机器转发“玩家真实 IP”给 zstdnet。", lineSeparator);
        appendLine(builder, "# frpc 和服务端在同一台机器时不用改，保持 127.0.0.1 即可。", lineSeparator);
        appendLine(builder, "# 如果 frpc 在另一台机器，就填那台机器连接到本服务器时使用的内网 IP。", lineSeparator);
        appendLine(builder, "trusted_proxy_ips=" + props.getProperty("trusted_proxy_ips"), lineSeparator);

        LinkedHashSet<String> extraKeys = new LinkedHashSet<>();
        for (String key : rawProps.stringPropertyNames()) {
            if (!KNOWN_KEYS.contains(key) && !"allow_raw_login".equals(key) && !isCommentFragmentKey(key, rawProps.getProperty(key, ""))) {
                extraKeys.add(key);
            }
        }

        if (!extraKeys.isEmpty()) {
            appendLine(builder, "", lineSeparator);
            appendLine(builder, "# 从旧配置中保留下来的额外自定义字段。", lineSeparator);
            for (String key : extraKeys) {
                appendLine(builder, key + "=" + rawProps.getProperty(key, ""), lineSeparator);
            }
        }

        Files.writeString(path, withUtf8Bom(builder.toString()), StandardCharsets.UTF_8);
    }

    private static void appendLine(StringBuilder builder, String line, String lineSeparator) {
        builder.append(line).append(lineSeparator);
    }

    private static String withUtf8Bom(String text) {
        return "\uFEFF" + stripUtf8Bom(text);
    }

    static String parseHost(String listen, String fallback) {
        String raw = listen == null ? "" : listen.trim();
        if (raw.isEmpty()) {
            return fallback;
        }

        if (raw.startsWith("[") && raw.contains("]")) {
            int end = raw.indexOf(']');
            return raw.substring(1, end).trim();
        }

        int idx = raw.lastIndexOf(':');
        if (idx > 0 && raw.indexOf(':') == idx) {
            return raw.substring(0, idx).trim();
        }
        return fallback;
    }

    static int parsePort(String listen, int fallback) {
        String raw = listen == null ? "" : listen.trim();
        try {
            if (raw.startsWith("[") && raw.contains("]")) {
                int end = raw.indexOf(']');
                if (end + 1 < raw.length() && raw.charAt(end + 1) == ':') {
                    return Integer.parseInt(raw.substring(end + 2).trim());
                }
                return fallback;
            }

            int idx = raw.lastIndexOf(':');
            if (idx > 0 && raw.indexOf(':') == idx) {
                return Integer.parseInt(raw.substring(idx + 1).trim());
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

}
