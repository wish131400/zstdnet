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

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side config used by the local publisher.
 */
public final class ClientConfig {
    public static final ModConfigSpec SPEC;

    private static final ModConfigSpec.IntValue LEVEL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        LEVEL = builder
            .comment("zstd compression level for client->server stream")
            .defineInRange("level", 3, 1, 22);

        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    public static int getLevel() {
        return LEVEL.get();
    }
}
