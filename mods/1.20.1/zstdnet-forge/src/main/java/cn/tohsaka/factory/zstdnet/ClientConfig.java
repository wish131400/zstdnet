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

package cn.tohsaka.factory.zstdnet;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 客户端配置模块。
 * <p>
 * 负责定义并读取 Forge 客户端侧的 zstdproxy 配置项：
 * URL（远端线路配置）与压缩等级。
 */
public final class ClientConfig {
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.ConfigValue<String> URL;
    private static final ForgeConfigSpec.IntValue LEVEL;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        URL = builder
                .comment("Remote JSON endpoint. Leave empty to use local servers.zstd.json")
                .define("url", "");

        LEVEL = builder
                .comment("zstd compression level for client->server stream")
                .defineInRange("level", 3, 1, 22);

        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    /**
     * 获取线路配置来源 URL。
     * 为空时表示使用本地 servers.zstd.json。
     */
    public static String getUrl() {
        String value = URL.get();
        return value == null ? "" : value.trim();
    }

    /**
     * 获取客户端上行压缩等级。
     */
    public static int getLevel() {
        return LEVEL.get();
    }
}
