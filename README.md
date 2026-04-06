# 关于本项目

ZstdNet 是一个 Minecraft Java 版 Forge 模组，用 ZSTD 对客户端与服务端之间的流量做压缩转发，目标是降低高重复数据场景下的公网带宽占用，尤其适合机械动力类、大型整合包类服务器。

## 依赖库

- zstd-jni: <https://github.com/luben/zstd-jni>
- Minecraft Forge: <https://files.minecraftforge.net/>

## 压缩效果

本模组设计之初是为了降低机械动力类服务器的大量重复数据发包带宽。在大型整合包环境中，公网带宽占用通常可以明显下降。

### 实际效果示例（服务器）

`network-compression-threshold=1048576` 时：

```text
Raw: 189.06 GB (3.6MB/s) | Zstd: 10.28 GB (252.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (5.0MB/s) | Zstd: 10.28 GB (234.3KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (3.2MB/s) | Zstd: 10.28 GB (215.5KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.07 GB (4.8MB/s) | Zstd: 10.28 GB (303.7KB/s) | Ratio: 5.44% | Conns: 8
```

## 使用方式

### 客户端

1. 客户端安装本模组后启动游戏。
2. 在“多人游戏”或“直接连接”中输入远程 `zstd` 地址，例如 `example.com:35565`。
3. 点击加入后，模组会自动在本地启动临时代理，再由本地代理接管后续连接。

### 服务端

首次启动后，会在 `config` 目录生成 `zstdnet-server.properties`。

- `listen` 作为公网 `zstd` 入口
- `target` 指向后端 Minecraft 或代理服务
- 玩家连接 `listen`
- 模组把流量解压/压缩后转发给 `target`

常用配置项如下：

| 配置项 | 含义 |
| --- | --- |
| `enabled=false` | 是否启用内置 zstd 服务端代理 |
| `listen=0.0.0.0:35565` | zstd 公网监听地址 |
| `target=127.0.0.1:25565` | 后端 Minecraft / Velocity 地址 |
| `level=9` | 服务端到客户端方向的 zstd 压缩级别 |
| `max_conn_per_ip=20` | 每个源 IP 的最大并发连接数 |
| `max_req_per_window=30` | 每个源 IP 在窗口期内的最大请求次数 |
| `request_window=10s` | 请求计数窗口 |
| `ban_duration=30m` | 超限后的封禁时长 |
| `stats_interval=1s` | 统计日志输出间隔 |
| `flush_interval=2ms` | zstd flush 间隔 |
| `idle_timeout=0s` | 空闲超时，`0s` 表示关闭 |
| `max_rate_per_conn_bps=0` | 单连接限速，`0` 为关闭 |
| `max_rate_global_bps=0` | 全局总限速，`0` 为关闭 |
| `burst_bytes=262144` | 令牌桶突发容量 |

如果你前面还有 `FRP`、`HAProxy` 一类转发层，建议明确梳理端口链路，确保公网入口最终转发到 `listen`，而不是直接转发到原版游戏端口。

## 客户端联机

这里将会介绍“玩家怎么连”和“房主怎么配”。

### 1. 普通玩家连接远程服务器

适用于已经有一台运行中的 `zstd` 服务端入口。

1. 客户端安装和服务器相同版本的整合包，并确保装有本模组。
2. 打开“多人游戏”或“直接连接”。
3. 服务器地址直接填写 `zstd` 入口地址，而不是后端原版端口。

示例：

```text
play.example.com:35565
1.2.3.4:35565
```

如果服务端配置是：

```properties
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

那么玩家应该连 `35565`，不要连 `25565`。

### 2. 使用 FRP 的典型链路

如果你用 FRP 做公网映射，推荐链路如下：

```text
玩家客户端 -> 公网 frp 端口 -> 主机上的 zstd listen -> 本地游戏端口
```

例如：

- 主机局域网游戏端口：`25565`
- 主机 zstd 监听端口：`35565`
- FRP 公网映射端口：`35565`

那么：

- `zstdnet-server.properties` 里把 `listen` 设为 `0.0.0.0:35565`
- `target` 设为 `127.0.0.1:25565`
- `frpc.toml` 把公网端口转发到主机的 `35565`
- 玩家最终填写公网 `35565`

不要把 FRP 直接转发到原版 `25565`，否则会绕过 zstd。

### 3. 客户端开局域网联机

适用于单机开房后，让别的玩家通过 zstd/隧道来加入你的世界。

现在模组已经支持在“对局域网开放”界面额外填写两个端口：

- 游戏端口
- Zstd 端口

含义如下：

- 游戏端口：Minecraft 局域网世界实际监听的端口
- Zstd 端口：ZstdNet 对外提供的压缩隧道端口

建议：

- 游戏端口用常规局域网端口，比如 `25565`
- Zstd 端口用另一个未占用端口，比如 `35565`

开房后，模组会把端口写入 `config/zstdnet-server.properties`，并支持热重载。

### 4. 如果局域网界面被别的模组覆盖

有些联机类模组会改写“对局域网开放”界面，导致额外输入框不显示，或者显示后保存行为被覆盖。

这时可以直接用客户端指令设置端口：

```text
/zstdport
/zstdport game 25565
/zstdport zstd 35565
```

说明：

- `/zstdport`：查看当前 Zstd 端口和游戏端口
- `/zstdport game <端口>`：设置后端游戏端口
- `/zstdport zstd <端口>`：设置 Zstd 对外端口

设置后会写入 `config/zstdnet-server.properties`。

### 5. 玩家应该填哪个地址

如果你是房主：

- 你本机世界实际监听的是“游戏端口”
- 对外给别人的是“Zstd 端口”

所以给朋友的地址应当是：

```text
你的公网 IP 或域名:Zstd 端口
```

例如：

```text
mc.example.com:35565
203.0.113.10:35565
```

而不是：

```text
mc.example.com:25565
```

除非你根本没有经过 zstd。

### 6. 联不上时先检查什么

优先排查这几项：

1. 玩家填的是不是 `Zstd 端口`，而不是原版游戏端口。
2. FRP 或其他隧道是不是转发到了 `listen` 端口。
3. `config/zstdnet-server.properties` 里的 `enabled` 是否为 `true`。
4. `listen` 和 `target` 是否写反。
5. Zstd 端口是否被其他程序占用。
6. 是否有其他模组拦截了登录或握手流程。

一个最常见的正确例子：

```properties
enabled=true
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

此时：

- 房主本地游戏在 `25565`
- ZstdNet 在 `35565`
- 朋友连 `35565`

## 工作原理

### 客户端侧

- 监听多人游戏界面和直接连接界面的加入动作
- 读取玩家输入的远程地址
- 先做地址合法性检查与 SRV 解析
- 在本地启动临时 loopback 代理
- Minecraft 实际连接本地代理
- 本地代理把首个握手包改写回远程目标地址，再进行 zstd 转发

### 服务端侧

- 监听公网 `zstd` 入口
- 识别进入连接是原版状态查询、原版登录还是 zstd 流量
- 原版状态查询直接透传，方便服务器列表 ping
- 原版登录直接断开并返回提示
- zstd 流量正常转发到后端 `target`
- 输出 `Raw / Zstd / Ratio / Conns` 等统计信息

## 常见问题

- 客户端是通过界面 hook 接管连接，如果多人游戏菜单或局域网菜单被其他模组大幅改写，理论上有冲突风险。
- 在线模式登录后链路会被加密，压缩收益通常会下降。
- 如果 `Ratio` 接近 `100%`，不一定是没生效，也可能只是数据本身不适合继续压缩。
- 某些认证类、封禁类、联机增强类模组也可能影响握手流程，遇到“能进服但很快掉线”时，需要连它们一起排查。

## 配置文件

- 客户端：`config/zstdnet-client.toml`
- 服务端：`config/zstdnet-server.properties`

## 第三方依赖说明

本项目依赖多个第三方组件，例如 Forge 与 `zstd-jni`。使用与分发时，请遵守它们各自的许可证条款。

> 本仓库协议仅覆盖本仓库自有代码，不替代第三方依赖许可证。

## 许可证

本项目采用 MIT 许可证，详见 `LICENSE`。
