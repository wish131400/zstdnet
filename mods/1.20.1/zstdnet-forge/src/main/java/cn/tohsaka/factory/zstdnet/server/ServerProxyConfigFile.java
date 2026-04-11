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

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class ServerProxyConfigFile {
    private static final int DEFAULT_MINECRAFT_PORT = 25565;
    private static final int DEFAULT_ZSTD_PORT = 25565;
    private static final int DEFAULT_BACKEND_PORT = 25566;
    private static final String DEFAULT_LISTEN_HOST = "0.0.0.0";

    private ServerProxyConfigFile() {
    }

    public static Path path() {
        return FMLPaths.GAMEDIR.get().resolve("config").resolve("zstdnet-server.properties");
    }

    public static int readListenPort() {
        return parsePort(loadProperties().getProperty("listen", "0.0.0.0:" + DEFAULT_ZSTD_PORT), DEFAULT_ZSTD_PORT);
    }

    public static int readTargetPort() {
        return parsePort(loadProperties().getProperty("target", "127.0.0.1:" + DEFAULT_MINECRAFT_PORT), DEFAULT_MINECRAFT_PORT);
    }

    public static void writeListenPort(int port) throws IOException {
        writePorts(port, null);
    }

    public static void writeTargetPort(int port) throws IOException {
        writePorts(null, port);
    }

    public static void writePorts(Integer listenPort, Integer targetPort) throws IOException {
        Properties props = loadProperties();
        String currentListen = props.getProperty("listen", "0.0.0.0:" + DEFAULT_ZSTD_PORT);
        String currentTarget = props.getProperty("target", "127.0.0.1:" + DEFAULT_MINECRAFT_PORT);
        Path path = path();
        Files.createDirectories(path.getParent());

        String listenHost = parseHost(currentListen, DEFAULT_LISTEN_HOST);
        String targetHost = parseHost(currentTarget, "127.0.0.1");
        String listenValue = listenHost + ":" + (listenPort != null ? listenPort : parsePort(currentListen, DEFAULT_ZSTD_PORT));
        String targetValue = targetHost + ":" + (targetPort != null ? targetPort : parsePort(currentTarget, DEFAULT_MINECRAFT_PORT));
        if (!Files.exists(path)) {
            Files.writeString(path, defaultConfigBody(listenValue, targetValue), StandardCharsets.UTF_8);
            return;
        }

        String text = Files.readString(path, StandardCharsets.UTF_8);
        String lineSeparator = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
        boolean hasEnabled = false;
        boolean hasAutoTakeover = false;
        boolean hasListen = false;
        boolean hasTarget = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            if (trimmed.startsWith("enabled=")) {
                lines.set(i, "enabled=true");
                hasEnabled = true;
                continue;
            }
            if (trimmed.startsWith("auto_takeover=")) {
                hasAutoTakeover = true;
                continue;
            }
            if (trimmed.startsWith("allow_raw_login=")) {
                lines.remove(i);
                i--;
                continue;
            }
            if (trimmed.startsWith("listen=")) {
                lines.set(i, "listen=" + listenValue);
                hasListen = true;
                continue;
            }
            if (trimmed.startsWith("target=")) {
                lines.set(i, "target=" + targetValue);
                hasTarget = true;
            }
        }

        if (!hasEnabled) {
            lines.add("enabled=true");
        }
        if (!hasAutoTakeover) {
            lines.add("auto_takeover=false");
        }
        if (!hasListen) {
            lines.add("listen=" + listenValue);
        }
        if (!hasTarget) {
            lines.add("target=" + targetValue);
        }

        Files.writeString(path, String.join(lineSeparator, lines), StandardCharsets.UTF_8);
    }

    private static Properties loadProperties() {
        Properties props = new Properties();
        Path path = path();
        if (!Files.exists(path)) {
            props.setProperty("enabled", "true");
            props.setProperty("auto_takeover", "true");
            props.setProperty("listen", "0.0.0.0:" + DEFAULT_ZSTD_PORT);
            props.setProperty("target", "127.0.0.1:" + DEFAULT_BACKEND_PORT);
            return props;
        }

        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException ignored) {
        }
        return props;
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

    private static String defaultConfigBody(String listenValue, String targetValue) {
        return """
            # zstdnet server config
            enabled=true
            auto_takeover=true
            listen=%s
            target=%s
            """.formatted(listenValue, targetValue);
    }
}
