package cn.tohsaka.factory.zstdnet.server;

import cn.tohsaka.factory.zstdnet.core.transport.ConnectionFloodGuard;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class ServerProxyConfigFile {
    private static final String DEFAULT_TRUSTED_PROXY_IPS = "127.0.0.1,::1,0:0:0:0:0:0:0:1";
    private static final Set<String> ACTIVE_KEYS = Set.of(
        "enabled", "level", "max_conn_per_ip", "max_req_per_window", "request_window",
        "ban_duration", "trust_proxy_protocol", "trusted_proxy_ips", "debug"
    );
    private static final Set<String> RETIRED_KEYS = Set.of(
        "legacy_proxy", "auto_takeover", "listen", "target", "udp_direct_ports",
        "stats_interval", "flush_interval", "idle_timeout",
        "max_rate_per_conn_bps", "max_rate_global_bps", "burst_bytes",
        "max_conn_total", "max_req_total_per_window", "max_worker_threads",
        "handshake_timeout", "raw_status_timeout", "status_cache_ttl",
        "voice_chat_passthrough", "voice_chat_listen", "voice_chat_target",
        "allow_raw_login"
    );

    private ServerProxyConfigFile() {
    }

    public static Path path() {
        return FMLPaths.GAMEDIR.get().resolve("config").resolve("zstdnet-server.properties");
    }

    public static boolean ensureExists() throws IOException {
        Path config = path();
        if (Files.exists(config)) {
            return false;
        }
        Files.createDirectories(config.getParent());
        writeConfigWithComments(config, new Properties(), System.lineSeparator());
        return true;
    }

    public static void compactIntegratedConfig() throws IOException {
        Path config = path();
        if (!Files.exists(config)) {
            return;
        }
        Properties props = loadProperties();
        if (!props.containsKey("level") || !props.containsKey("debug") || RETIRED_KEYS.stream().anyMatch(props::containsKey)) {
            writeConfigWithComments(config, props, detectLineSeparator(config));
        }
    }

    public static boolean readEnabled() {
        return Boolean.parseBoolean(loadProperties().getProperty("enabled", "true").trim());
    }

    public static int readLevel() {
        return parseLevel(loadProperties().getProperty("level"));
    }

    static int parseLevel(String raw) {
        int level = parseInt(raw, 9);
        return level >= 1 && level <= 22 ? level : 9;
    }

    public static ConnectionFloodGuard createFloodGuard() {
        Properties props = loadProperties();
        return new ConnectionFloodGuard(
            parseInt(props.getProperty("max_conn_per_ip"), 64),
            parseInt(props.getProperty("max_req_per_window"), 50),
            parseDurationMillis(props.getProperty("request_window"), 10_000L),
            parseDurationMillis(props.getProperty("ban_duration"), 60_000L)
        );
    }

    public static boolean readTrustProxyProtocol() {
        return Boolean.parseBoolean(loadProperties().getProperty("trust_proxy_protocol", "false").trim());
    }

    public static boolean readDebug() {
        return Boolean.parseBoolean(loadProperties().getProperty("debug", "false").trim());
    }

    public static Set<String> readTrustedProxyIps() {
        String raw = loadProperties().getProperty("trusted_proxy_ips", DEFAULT_TRUSTED_PROXY_IPS);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : raw.split(",")) {
            if (!value.isBlank()) {
                values.add(value.trim());
            }
        }
        return Set.copyOf(values);
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static long parseDurationMillis(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1000L;
        if (value.endsWith("ms")) {
            multiplier = 1L;
            value = value.substring(0, value.length() - 2);
        } else if (value.endsWith("s")) {
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = 60_000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("h")) {
            multiplier = 3_600_000L;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("d")) {
            multiplier = 86_400_000L;
            value = value.substring(0, value.length() - 1);
        }
        try {
            return Math.max(0L, Math.multiplyExact(Long.parseLong(value), multiplier));
        } catch (NumberFormatException | ArithmeticException e) {
            return fallback;
        }
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        Path config = path();
        if (!Files.exists(config)) {
            return props;
        }
        try (Reader reader = new StringReader(stripBom(Files.readString(config, StandardCharsets.UTF_8)))) {
            props.load(reader);
        } catch (IOException ignored) {
        }
        return props;
    }

    private static String stripBom(String text) {
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static String detectLineSeparator(Path config) throws IOException {
        return Files.readString(config, StandardCharsets.UTF_8).contains("\r\n") ? "\r\n" : "\n";
    }

    static void writeConfigWithComments(Path config, Properties raw, String newline) throws IOException {
        Properties props = new Properties();
        if (raw != null) {
            props.putAll(raw);
        }
        props.putIfAbsent("enabled", "true");
        props.putIfAbsent("level", "9");
        props.putIfAbsent("max_conn_per_ip", "64");
        props.putIfAbsent("max_req_per_window", "50");
        props.putIfAbsent("request_window", "10s");
        props.putIfAbsent("ban_duration", "1m");
        props.putIfAbsent("trust_proxy_protocol", "false");
        props.putIfAbsent("trusted_proxy_ips", DEFAULT_TRUSTED_PROXY_IPS);
        props.putIfAbsent("debug", "false");

        StringBuilder out = new StringBuilder();
        line(out, "# ZstdNet 服务端配置", newline);
        line(out, "# 玩家使用 Minecraft 的原游戏端口；UDP 模组按其自身配置连接。", newline);
        line(out, "", newline);
        line(out, "# 是否启用 ZstdNet 压缩。", newline);
        line(out, "enabled=" + props.getProperty("enabled"), newline);
        line(out, "# 服务端到客户端的 Zstd 压缩等级（1-22，默认 9；越高通常越省流量，但更耗 CPU）。", newline);
        line(out, "level=" + props.getProperty("level"), newline);
        line(out, "", newline);
        line(out, "# 单个 IP 的最大并发连接数。<=0 表示不限制。", newline);
        line(out, "max_conn_per_ip=" + props.getProperty("max_conn_per_ip"), newline);
        line(out, "# 单个 IP 在 request_window 内的最大连接请求数。<=0 表示不限制。", newline);
        line(out, "max_req_per_window=" + props.getProperty("max_req_per_window"), newline);
        line(out, "# 连接请求计数时间窗口。", newline);
        line(out, "request_window=" + props.getProperty("request_window"), newline);
        line(out, "# 超过请求次数限制后的拒绝时长。", newline);
        line(out, "ban_duration=" + props.getProperty("ban_duration"), newline);
        line(out, "", newline);
        line(out, "# 开启或关闭正版验证均生效：反代发送 PROXY v2 时设为 true。", newline);
        line(out, "# 普通直连和局域网保持 false。", newline);
        line(out, "trust_proxy_protocol=" + props.getProperty("trust_proxy_protocol"), newline);
        line(out, "# 开启或关闭正版验证均生效：允许转发真实 IP 的代理机器地址。", newline);
        line(out, "trusted_proxy_ips=" + props.getProperty("trusted_proxy_ips"), newline);
        line(out, "", newline);
        line(out, "# 调试日志：输出 PROXY v2 真实 IP 转发成功记录。默认 false；修改后重启服务端生效。", newline);
        line(out, "debug=" + props.getProperty("debug"), newline);

        if (raw != null) {
            LinkedHashSet<String> extras = new LinkedHashSet<>();
            for (String key : raw.stringPropertyNames()) {
                if (!ACTIVE_KEYS.contains(key) && !RETIRED_KEYS.contains(key)
                        && !key.stripLeading().startsWith("#") && !key.stripLeading().startsWith("!")) {
                    extras.add(key);
                }
            }
            if (!extras.isEmpty()) {
                line(out, "", newline);
                line(out, "# 保留的自定义字段。", newline);
                for (String key : extras) {
                    line(out, key + "=" + raw.getProperty(key, ""), newline);
                }
            }
        }
        Files.createDirectories(config.getParent());
        Files.writeString(config, "\uFEFF" + out, StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder out, String value, String newline) {
        out.append(value).append(newline);
    }
}
