# ZstdNet

ZstdNet 是一个 Minecraft Java 版模组，用 ZSTD 压缩客户端与服务端之间的转发流量，目标是在高重复数据场景下显著降低公网带宽占用。

它尤其适合：

- 机械动力类服务器
- 大型整合包服务器
- 需要走 FRP / 内网穿透 / 隧道转发的联机场景
- 单机开房后希望给朋友提供更省带宽入口的场景

## 这个模组能做什么

- 客户端输入 ZstdNet 地址后，自动在本地启动临时代理并接管连接
- 服务端提供独立的 Zstd 入口，把压缩流量转发到后端 Minecraft 端口
- 支持专用服，也支持单机开放局域网后的房主使用
- 自带 HUD，可在游戏内查看当前是否正在走 zstd，以及实时流量情况
- 支持原版状态查询透传，方便服务器列表正常 ping

## 实际效果

这个模组设计的重点，就是降低高重复数据包带来的公网带宽消耗。

例如在大型整合包[齿轮盛宴官方网站](https://www.xn--dctt54dhmrbwo.com)中，压缩收益通常会非常明显。下面是一组服务器侧的实际统计示例：

```text
Raw: 189.06 GB (3.6MB/s) | Zstd: 10.28 GB (252.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (5.0MB/s) | Zstd: 10.28 GB (234.3KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (3.2MB/s) | Zstd: 10.28 GB (215.5KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.07 GB (4.8MB/s) | Zstd: 10.28 GB (303.7KB/s) | Ratio: 5.44% | Conns: 8
```

**Ratio 越低，说明压缩后的实际传输量越小。**

## 安装说明

推荐客户端和服务端都安装本模组。

- 普通连接远程 ZstdNet 服务器时：客户端需要安装
- 使用内置 Zstd 服务端入口时：服务端需要安装
- 单机开放局域网并对外分享 Zstd 入口时：房主客户端需要安装

## 普通玩家怎么连接

如果服主已经配置好了 ZstdNet，那么玩家加入时直接填写 **Zstd 入口地址**，而不是原版游戏端口。

例如：

```text
play.example.com:35565
1.2.3.4:35565
```

如果服主的配置是：

```properties
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

那么玩家应该连接 **35565**，不要连接 **25565**。

## 必要准备

在专用服上使用内置 ZstdNet 服务端入口之前，请先确认后端 Minecraft 服务器已经正确配置。

在服务器的 `server.properties` 中设置：

```properties
online-mode=false
network-compression-threshold=1048576
```

- `online-mode=false`：关闭后端服务器的原版正版验证
- `network-compression-threshold=1048576`：基本关闭原版网络压缩，让 ZstdNet 接管压缩

如果后端服务器继续启用原版验证或原版网络压缩，连接可能失败，或者压缩收益会明显低于预期。

如果你仍然希望保留正版校验能力，可以额外搭配 [TrueUUID（正版离线共存）](https://www.mcmod.cn/class/21953.html)。

- 这个模组适合“后端保持 `online-mode=false`，但登录阶段仍然执行正版校验”的场景
- 可以在离线模式下尽量保留正版 UUID、名称大小写与皮肤属性等信息
- 对于需要离线转发、内网穿透、代理链路，同时又不想完全放弃正版验证的服务器来说会比较实用

也就是说，ZstdNet 负责接管压缩与转发；如果你还需要正版验证，可以再配合 TrueUUID 这类模组一起使用。

## 专用服怎么配置

首次启动后，会在 `config` 目录生成：

```text
config/zstdnet-server.properties
```

最常见的配置示例：

```properties
enabled=true
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

含义：

- `listen`：ZstdNet 对外提供的压缩入口
- `target`：后端 Minecraft 或代理实际监听的端口

也就是说：

- 玩家连接 `listen`
- ZstdNet 解压/压缩后再把流量转发给 `target`

如果你前面还有 FRP、HAProxy、内网穿透或其他转发层，请确保公网入口最终转发到 `listen`，不要直接转发到原版游戏端口，否则会绕过 zstd。

## FRP 典型链路

推荐链路：

```text
玩家客户端 -> 公网 FRP 端口 -> 主机上的 ZstdNet listen 端口 -> 本地游戏端口
```

例如：

- 游戏端口：`25565`
- ZstdNet 端口：`35565`
- FRP 公网端口：`35565`

那么推荐这样配置：

- 在 `zstdnet-server.properties` 中把 `listen` 设为 `0.0.0.0:35565`
- 把 `target` 设为 `127.0.0.1:25565`
- 在 `frpc.toml` 中把公网端口转发到主机的 `35565`
- 玩家最终连接公网 `35565`

## 单机 / 局域网开房

ZstdNet 支持在“对局域网开放”界面额外填写两个端口：

- 游戏端口
- Zstd 端口

建议：

- 游戏端口使用常规端口，比如 `25565`
- Zstd 端口使用另一个未占用端口，比如 `35565`

开房后，模组会把设置写入：

```text
config/zstdnet-server.properties
```

并支持热重载。

如果朋友要从外网加入，请把下面这个地址发给对方：

```text
你的公网 IP 或域名:Zstd 端口
```

例如：

```text
mc.example.com:35565
203.0.113.10:35565
```

## 指令

### `/zstdhud`

用于查看或切换 HUD：

```text
/zstdhud
/zstdhud on
/zstdhud off
/zstdhud toggle
```

### `/zstdport`

用于查看或修改单机 / 局域网场景下的端口：

```text
/zstdport show
/zstdport game 25565
/zstdport zstd 35565
```

注意：

- `/zstdport` 是客户端指令
- 只有本地房主且有管理员权限时才能修改
- 专用服不会通过这个指令改服务器配置
- 专用服请直接修改 `config/zstdnet-server.properties`

## HUD 面板

开启 `zstdhud` 后，可以直接在游戏里看到：

- 当前连接模式
- 监听地址或远程目标地址
- 压缩后实时速率
- 原始实时速率
- 累计传输量
- 压缩率
- 当前连接数

如果你想确认当前到底有没有走 zstd，HUD 是最直观的判断方式。

## 常见问题

### 为什么我进不去服务器？

优先检查这些问题：

1. 玩家填写的是不是 `Zstd 端口`，而不是原版游戏端口。
2. FRP 或其他隧道是不是转发到了 `listen` 端口。
3. `config/zstdnet-server.properties` 里的 `enabled=true` 是否已设置。
4. `listen` 和 `target` 是否写反。
5. Zstd 端口是否已被其他程序占用。
6. 是否有其他模组拦截了登录或握手流程。
7. 局域网场景下，游戏端口和配置里的 `target` 是否一致。

### 压缩率接近 100% 是不是没生效？

不一定。

有些流量本身就不适合继续压缩，或者已经被加密，收益会明显下降。

### 会不会和别的联机模组冲突？

有可能。

客户端这边是通过界面接管连接流程实现的，如果多人游戏菜单或“对局域网开放”界面被其他模组大幅改写，理论上存在兼容性风险。

## 配置文件

- 客户端：`config/zstdnet-client.toml`
- 服务端：`config/zstdnet-server.properties`

### 服务端配置文件 `zstdnet-server.properties` 内容

```properties
# ------------------------------------------------------------
# zstdnet 内置服务端配置（自动生成）
# ------------------------------------------------------------
# 1) 先确认 listen / target，再把 enabled 改为 true。
# 2) listen 与 target 不能是同一个端点。
# 3) 地址不要写成 127.0.0.1.（末尾带点会解析失败）。

# 是否启用内置 zstd 代理。
enabled=true

# zstd 公网监听入口。
listen=0.0.0.0:35565

# 后端 Minecraft / Velocity 地址。
target=127.0.0.1:25565

# zstd 压缩等级（1-22，通常建议 3-9）。
level=9

# 单个 IP 最大并发连接数（<=0 表示关闭限制）。
max_conn_per_ip=20

# 单个 IP 在 request_window 内最大请求次数（<=0 表示关闭限制）。
max_req_per_window=30

# 请求计数时间窗口。
request_window=10s

# 超限后的封禁时长。
ban_duration=30m

# 统计日志输出间隔。
stats_interval=1s

# zstd flush 间隔，0ms 表示每次写入都 flush。
flush_interval=2ms

# 后端读取的空闲超时时间，0 表示禁用。
idle_timeout=0

# 单连接限速（字节/秒，0 表示关闭）。
max_rate_per_conn_bps=0

# 全局总限速（字节/秒，0 表示关闭）。
max_rate_global_bps=0

# 令牌桶突发容量（字节）。
burst_bytes=262144
```

**配置项说明：**

- `enabled`：是否开启 ZstdNet 服务（默认：true）
  - 设为 true 才能使用 Zstd 压缩功能

- `listen`：Zstd 压缩入口的地址和端口（默认：0.0.0.0:35565）
  - 玩家连接游戏时用的就是这个地址和端口
  - 0.0.0.0 表示允许所有IP访问

- `target`：后端 Minecraft 服务器的地址和端口（默认：127.0.0.1:25565）
  - ZstdNet 会把压缩后的流量转发到这个地址
  - 127.0.0.1 表示本地服务器

- `level`：压缩强度（1-22，建议 3-9，默认：9）
  - 数字越大，压缩效果越好，但会占用更多 CPU
  - 一般设置 3-5 就足够了，平衡性能和压缩效果

- `max_conn_per_ip`：每个 IP 最多能同时连接的数量（默认：20）
  - 设为 0 或负数表示不限制
  - 防止单个 IP 占用过多连接

- `max_req_per_window`：每个 IP 在一定时间内最多能发起的请求次数（默认：30）
  - 设为 0 或负数表示不限制
  - 防止恶意刷请求

- `request_window`：请求计数的时间范围（默认：10s）
  - 配合 max_req_per_window 使用，比如 10s 内最多 30 次请求

- `ban_duration`：超过限制后封禁的时间（默认：30m）
  - 防止恶意攻击，被封禁的 IP 暂时无法连接

- `stats_interval`：服务器日志显示流量统计的间隔（默认：1s）
  - 每隔 1 秒在控制台显示一次流量情况

- `flush_interval`：数据压缩后多久发送一次（默认：2ms）
  - 设为 0 表示每次压缩后立即发送
  - 数值越小，延迟越低，但可能增加网络开销

- `idle_timeout`：后端连接的空闲超时时间（默认：0）
  - 设为 0 表示不超时，保持连接一直活跃
  - 非 0 值表示如果连接空闲超过这个时间就自动断开

- `max_rate_per_conn_bps`：每个连接的最大速度限制（默认：0）
  - 单位是字节/秒，设为 0 表示不限制
  - 防止单个连接占用过多带宽

- `max_rate_global_bps`：所有连接的总速度限制（默认：0）
  - 单位是字节/秒，设为 0 表示不限制
  - 控制整体带宽使用

- `burst_bytes`：允许的突发流量大小（默认：262144）
  - 单位是字节，相当于流量的"缓冲池"
  - 即使设置了限速，短时间内的突发流量也可以超过限制

### 客户端配置文件 `zstdnet-client.toml` 内容

```toml
# Configuration file

[general]
	# zstd compression level for client->server stream
	level = 3
```

**配置项说明：**

- `level`：客户端到服务端流的 Zstd 压缩级别（默认：3，范围：1-22）
  - 级别越高，压缩率越好，但 CPU 使用率也会增加
  - 建议在 3-5 之间选择，平衡压缩效果和性能

## 依赖

- Minecraft Forge
- zstd-jni

## License

本项目采用 MIT License。
