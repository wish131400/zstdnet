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

package cn.tohsaka.factory.zstdnet;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side config used by the local publisher.
 */
public final class ClientConfig {
    private static final int DEFAULT_LEVEL = 3;
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("zstdnet-client.toml");

    private static volatile int level = DEFAULT_LEVEL;
    private static volatile boolean initialized;

    private ClientConfig() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        synchronized (ClientConfig.class) {
            if (initialized) {
                return;
            }
            level = loadOrCreate();
            initialized = true;
        }
    }

    public static int getLevel() {
        if (!initialized) {
            init();
        }
        return level;
    }

    private static int loadOrCreate() {
        try {
            Files.createDirectories(PATH.getParent());
            if (!Files.exists(PATH)) {
                Files.writeString(PATH, defaultConfigBody(), StandardCharsets.UTF_8);
                return DEFAULT_LEVEL;
            }
        } catch (IOException ignored) {
            return DEFAULT_LEVEL;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(PATH)) {
            properties.load(input);
        } catch (IOException ignored) {
            return DEFAULT_LEVEL;
        }
        return clamp(parseInt(properties.getProperty("level"), DEFAULT_LEVEL), 1, 22);
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String defaultConfigBody() {
        return """
            # ZstdNet client config
            # zstd compression level for the local client proxy
            level=%d
            """.formatted(DEFAULT_LEVEL);
    }
}
