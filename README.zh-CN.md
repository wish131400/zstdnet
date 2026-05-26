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
- 客户端本地代理会在同一个本地端口提供 UDP 原样透传，用于兼容 Sable / 机械动力：航空学这类依赖 Minecraft 同端口 UDP 的模组

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

- 当前仓库已同步支持：
  Forge 1.20.1、NeoForge 1.20.1、NeoForge 1.21.1、Fabric 1.20.1、Fabric 1.21.1
- 普通连接远程 ZstdNet 服务器时：客户端需要安装
- 使用内置 Zstd 服务端入口时：服务端需要安装
- 单机开放局域网并对外分享 Zstd 入口时：房主客户端需要安装

## 普通玩家怎么连接

如果服主使用默认推荐的 `auto_takeover=true`，那么玩家通常继续填写服主原来公开的那个地址和端口即可，不需要额外学习一套新端口。

例如：

```text
play.example.com:25565
1.2.3.4:25565
```

也就是说在默认自动接管模式下：

- 玩家继续连接 `server.properties` 里的公网端口
- ZstdNet 会自动接管这个端口
- 后端 Minecraft 会被自动挪到另一个本地端口

只有当服主手动关闭 `auto_takeover`，改成手动模式时，玩家才需要连接 `listen` 里单独配置的 Zstd 端口，比如：

```properties
auto_takeover=false
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

## 必要准备

在专用服上使用内置 ZstdNet 服务端入口之前，请先确认后端 Minecraft 服务器已经正确配置。

在服务器的 `server.properties` 中至少设置：

```properties
online-mode=false
```

- `online-mode=false`：关闭后端服务器的原版正版验证
- `network-compression-threshold=1048576`：专用服启用内置 ZstdNet 服务端入口后，模组会在启动时自动接管这一项，通常不需要再手动填写

如果后端服务器继续启用原版验证，连接可能失败；如果原版网络压缩没有被 ZstdNet 接管，压缩收益也会明显低于预期。

如果你仍然希望保留正版校验能力，可以额外搭配 [TrueUUID（正版离线共存）](https://www.curseforge.com/minecraft/mc-mods/trueuuid)。

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
auto_takeover=true
```

含义：

- `auto_takeover=true`：启动时读取 `server.properties` 里的 `server-port`，直接把它接管成公网入口
- `listen` / `target`：自动模式下由 ZstdNet 在运行时内部解析，通常不需要手填

也就是说：

- 玩家继续连接原来的公网端口
- ZstdNet 会在内部自动接管并转发到后端端口

如果你前面还有 FRP、HAProxy、内网穿透或其他转发层，请确保公网入口最终转发到 `listen`，不要直接转发到原版游戏端口，否则会绕过 zstd。

如果整合包包含 Sable / 机械动力：航空学等依赖同端口 UDP 的模组，也请确保前置转发层同时转发同一个端口的 TCP 和 UDP。ZstdNet 会在客户端本地 `127.0.0.1:<本地代理端口>` 上同时监听 TCP 和 UDP，并把 UDP 原样转发到远端同端口；如果公网隧道只转发 TCP，Sable 会回退到性能较差的 TCP 管线。

默认推荐直接使用 `auto_takeover=true`。这样模组会在启动时读取 `server.properties` 里的 `server-port`，把它作为公网入口 `listen`，再自动把后端 Minecraft 挪到另一个本地空闲端口 `target`，服主通常不需要再手动填写端口。

## FRP 典型链路

推荐链路：

```text
玩家客户端 -> 公网 FRP 端口 -> 主机上的 ZstdNet listen 端口 -> 本地游戏端口
```

例如：

- 游戏端口：`25565`
- 公网入口：`25565`
- 后端游戏端口：`25566`（如果空闲会自动分配）
- FRP 公网端口：`25565`

那么推荐这样配置：

- 在 `zstdnet-server.properties` 中保持 `auto_takeover=true`
- 公网入口最终转发到当前 `listen`
- 后端 `target` 由 ZstdNet 在启动时自动分配
- 玩家最终继续连接原来的公网端口

## 真实 IP / FRP PROXY v2 怎么选

如果玩家是直接连到 zstdnet 入口端口，比如公网直连、局域网直连、虚拟局域网直连，就保持：

```properties
trust_proxy_protocol=false
```

这种情况下 zstdnet 会直接从 TCP 连接里拿到玩家 IP：公网直连看到玩家公网 IP，局域网 / 虚拟局域网直连看到玩家的内网或虚拟网 IP。

只有当前面还有 FRP / 反代，并且你希望后端看到玩家真实 IP 时，才开启：

```properties
trust_proxy_protocol=true
trusted_proxy_ips=127.0.0.1,::1,0:0:0:0:0:0:0:1
```

同时 FRP TCP 映射需要开启 PROXY Protocol v2，例如：

```toml
transport.proxyProtocolVersion = "v2"
```

`trusted_proxy_ips` 填的是“有资格告诉 zstdnet 玩家真实 IP 的前置代理机器 IP”，不是玩家 IP，也不是允许进服的网段。frpc 和服务端在同一台机器时保持默认即可；如果 frpc 在另一台机器，就填那台机器连接到本服务器时使用的内网 / 虚拟局域网 IP。

注意：`trust_proxy_protocol=true` 后，直接连 zstdnet 入口端口但不带 PROXY v2 头的连接会被拒绝，例如本机、局域网、虚拟局域网或公网直连 `listen` 端口都会进不去。这是为了防止别人伪造真实 IP。

## 单机 / 局域网开房

单机世界“对局域网开放”时，ZstdNet 会在界面中加入 Zstd 端口输入框，并兼容原版或高级联机界面里的游戏端口输入框。

推荐用法：

- 游戏端口通常可以留空，除非你明确需要固定本次 Minecraft 局域网端口；ZstdNet 会自动跟随本次实际开放的 LAN 端口。
- Zstd 端口优先使用配置里的端口；如果该端口被占用，或刚好被本次 LAN/语音端口占用，运行时会自动换到可用端口。
- 开放成功后，聊天框会提示本次实际使用的 Zstd 端口；提示里的 Zstd 端口可以点击复制。

当前生效设置会写入：

```text
config/zstdnet-server.properties
```

并支持热重载。
和专用服不同，这个场景下配置文件通常会直接写出当前偏好的 `listen` / `target`；如果运行时因为端口冲突自动换端口，请以聊天框提示和 `/zstdport show` 显示的实际端口为准。

如果朋友要从外网加入，请把下面这个地址发给对方：

```text
你的公网 IP 或域名:Zstd 端口
```

例如：

```text
mc.example.com:35565
203.0.113.10:35565
```

如果某个高级联机模组完全替换了开房界面，导致 Zstd 输入框不可见，可以用 `/zstdport show` 查看当前端口；只有需要固定公网/隧道端口时，才需要使用 `/zstdport zstd <端口>` 手动指定。

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
/zstdport voice 25565
/zstdport zstdvoice 24455
```

注意：

- `/zstdport` 是客户端指令
- `show` 可以查看当前配置
- `voice` 修改 `voice_chat_target`，也就是后端语音端口
- `zstdvoice` 修改 `voice_chat_listen`，也就是公网语音入口
- 单机开房 / LAN 模式下，默认 `voice_chat_target` 是 `127.0.0.1:25565`
- 专用服默认 `voice_chat_target` 仍然是 `127.0.0.1:24454`
- `game`、`zstd`、`voice`、`zstdvoice` 只有本地房主且有管理员权限时才能修改
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

1. 如果 `auto_takeover=true`，玩家继续填写原来 `server.properties` 里的公网端口即可；如果 `auto_takeover=false`，再确认玩家填写的是不是配置里的 `listen` 端口。
2. FRP 或其他隧道是不是转发到了 `listen` 端口。
3. `config/zstdnet-server.properties` 里的 `enabled=true` 是否已设置。
4. `listen` 和 `target` 是否写反。
5. Zstd 端口是否已被其他程序占用。
6. 是否有其他模组拦截了登录或握手流程。
7. 局域网场景下，请优先使用聊天框提示的 Zstd 端口；游戏端口通常可以留空，因为 ZstdNet 会自动跟随本次实际 LAN 端口。

### 压缩率接近 100% 是不是没生效？

不一定。

有些流量本身就不适合继续压缩，或者已经被加密，收益会明显下降。

### 会不会和别的联机模组冲突？

有可能。

如果多人游戏菜单或“对局域网开放”界面被其他模组大幅改写，理论上仍然存在兼容性风险。

### Simple Voice Chat 这类语音模组能不能用？

可以，但语音流量不会走 zstd 压缩，只会在服务端做原样 UDP 转发。

- 如果 Simple Voice Chat 使用独立 UDP 端口，就必须显式填写 `voice_chat_listen`；`voice_chat_target` 可以留空让模组自动指向本机的 SVC 端口。
- 如果语音 UDP 路由没有成功启动，游戏本身的 TCP 连接仍然可以正常工作，只是语音会离线。
- 如果你想直接改 `zstdnet-server.properties` 里的语音转发项，可以用 `/zstdport voice <端口>` 修改后端语音端口，或用 `/zstdport zstdvoice <端口>` 修改公网语音入口。

## 配置文件

- 客户端：`config/zstdnet-client.toml`
- 服务端：`config/zstdnet-server.properties`
- `zstdnet-server.properties` 会由模组自动维护；命令改端口或自动接管时，会按内置的带注释模板重新写回配置文件。

**配置项说明：**

- `enabled`：是否开启 ZstdNet 服务（默认：true）
  - 设为 true 才能使用 Zstd 压缩功能

- `auto_takeover`：是否自动读取 `server.properties` 里的 `server-port` 并接管为公网入口（默认：true）
  - 开启后，专用服通常不需要再手动填写 `listen` / `target`
  - ZstdNet 会在启动时自动把后端 Minecraft 挪到另一个本地空闲端口

- 专用服自动模式：配置文件通常不会固定写出 `listen` / `target`
  - `listen` 会在启动时解析成当前公网入口
  - `target` 会在启动时解析成自动分配的本地后端端口
  - 不要把运行中的 `target` 直接暴露到公网

- `listen`：手动模式下的 Zstd 压缩入口地址和端口
  - 当 `auto_takeover=false` 时，玩家连接游戏时用的就是这个地址和端口
  - 0.0.0.0 表示允许所有IP访问

- `target`：手动模式下后端 Minecraft 服务器的地址和端口
  - ZstdNet 会把压缩后的流量转发到这个地址
  - 127.0.0.1 表示本地服务器

- 单机 / 局域网开房：配置文件通常会直接保留 `listen` / `target`
  - 这是为了让房主直接看到当前分享出去的 zstd 端口和本地游戏端口
  - 通过 `/zstdport` 修改后，这两个值也会随之更新

- `level`：压缩强度（1-22，建议 3-9，默认：9）
  - 数字越大，压缩效果越好，但会占用更多 CPU
  - 一般设置 3-5 就足够了，平衡性能和压缩效果

- `max_conn_per_ip`：每个 IP 最多能同时连接的数量（默认：9999）
  - 设为 0 或负数表示不限制
  - 防止单个 IP 占用过多连接

- `max_req_per_window`：每个 IP 在一定时间内最多能发起的请求次数（默认：50）
  - 设为 0 或负数表示不限制
  - 防止恶意刷请求

- `request_window`：请求计数的时间范围（默认：10s）
  - 配合 max_req_per_window 使用，比如 10s 内最多 50 次请求

- `ban_duration`：超过限制后封禁的时间（默认：1m）
  - 防止恶意攻击，被封禁的 IP 暂时无法连接

- `stats_interval`：服务器日志显示流量统计的间隔（默认：0s，关闭统计日志）
  - 设置为正数间隔时，在控制台定时显示流量情况

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

- `voice_chat_passthrough`：是否为 Simple Voice Chat 启用原样 UDP 转发（默认：true）
  - 语音流量不会经过 zstd 压缩
  - 设为 false 后将完全关闭额外的语音 UDP 路由处理

- `voice_chat_listen`：语音的公网 UDP 入口（可选）
  - 单机开房 / LAN 模式下，生成出来的默认值通常是 `0.0.0.0:24455`
  - 独立语音端口模式下必须显式填写这里

- `voice_chat_target`：语音的后端 UDP 目标（可选）
  - 单机开房 / LAN 模式下，生成出来的默认值是 `127.0.0.1:25565`
  - 专用服生成出来的默认值是 `127.0.0.1:24454`
  - 独立语音端口模式下，若 `voice_chat_listen` 已填写，默认会指向 `127.0.0.1:<SVC端口>`

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

- zstd-jni

## License

本项目采用 MIT License。
