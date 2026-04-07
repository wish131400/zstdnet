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

package cn.tohsaka.factory.zstdnet.coremod;

import net.minecraft.server.MinecraftServer;

public final class LanCompressionHooks {
    public static final int VANILLA_THRESHOLD = 256;
    public static final int LAN_THRESHOLD = 1048576;

    private LanCompressionHooks() {
    }

    public static int resolveLanCompressionThreshold(MinecraftServer server) {
        if (server != null && !server.isDedicatedServer() && server.isPublished()) {
            return LAN_THRESHOLD;
        }
        return VANILLA_THRESHOLD;
    }
}
