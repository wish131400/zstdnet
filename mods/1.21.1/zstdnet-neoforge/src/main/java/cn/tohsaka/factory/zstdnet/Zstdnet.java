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

import cn.tohsaka.factory.zstdnet.client.ClientProxyPublisher;
import cn.tohsaka.factory.zstdnet.network.LanCompressionSync;
import cn.tohsaka.factory.zstdnet.server.ServerProxyBootstrap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组主入口。
 * <p>
 * 客户端侧初始化线路发布器，服务端侧初始化内置代理启动器。
 */
@Mod(Zstdnet.MODID)
public class Zstdnet {
    public static final String MODID = "zstdnet";

    private static final Logger LOGGER = LoggerFactory.getLogger(Zstdnet.class);

    /**
     * 模组加载时注册客户端与服务端功能。
     */
    public Zstdnet(IEventBus modEventBus, ModContainer modContainer) {
        LanCompressionSync.init(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientProxyPublisher.init(modContainer);
        }
        ServerProxyBootstrap.init();
        LOGGER.info("zstdnet loaded");
    }
}
