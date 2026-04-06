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

import java.util.List;

/**
 * 线路配置数据模型。
 *
 * @param servers 客户端可发布的 zstd 线路列表
 */
public record ZstdServerList(List<ZstdServer> servers) {
    /**
     * 单条线路定义。
     *
     * @param name 显示名
     * @param addr 远端代理地址（host:port）
     * @param mask 线路唯一标识
     * @param icon 服务器图标（可选 base64）
     */
    public record ZstdServer(String name, String addr, String mask, String icon, String mode, String statusAddr) {
    }
}
