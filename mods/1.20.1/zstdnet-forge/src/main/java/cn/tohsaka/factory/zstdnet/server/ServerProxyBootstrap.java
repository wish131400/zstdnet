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

package cn.tohsaka.factory.zstdnet.server;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端引导模块。
 * <p>
 * 监听 Forge 服务器生命周期，在专用服启动/停止时控制内置 zstd 代理运行时。
 */
public final class ServerProxyBootstrap {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final ServerProxyRuntime RUNTIME = new ServerProxyRuntime();

    private ServerProxyBootstrap() {
    }

    /**
     * 注册服务端事件监听器（只注册一次）。
     */
    public static void init() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }
        MinecraftForge.EVENT_BUS.addListener(ServerProxyBootstrap::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(ServerProxyBootstrap::onServerStopping);
        LOGGER.info("zstdnet server bootstrap initialized");
    }

    /**
     * 专用服启动后启动代理运行时。
     */
    private static void onServerStarted(ServerStartedEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        RUNTIME.start(event.getServer().getPort());
    }

    /**
     * 专用服停止前关闭代理运行时。
     */
    private static void onServerStopping(ServerStoppingEvent event) {
        if (!event.getServer().isDedicatedServer()) {
            return;
        }
        RUNTIME.stop();
    }
}
