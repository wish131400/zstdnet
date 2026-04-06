# 关于本项目

ZstdNet 是一个 Minecraft Java 版 Forge 模组，用 ZSTD 对客户端与服务端之间的流量做压缩转发，目标是降低高重复数据场景下的公网带宽占用，尤其适合机械动力类、大型整合包类服务器。

## 依赖库

- zstd-jni: <https://github.com/luben/zstd-jni>
- Minecraft Forge: <https://files.minecraftforge.net/>

## 压缩效果

本模组设计之初是为了降低机械动力类服务器的大量重复数据发包带宽。在整合包齿轮盛宴中，多台官方服务器的压缩效果较明显，平均带宽降低约 `80%~90%`。

### 实际效果示例

`network-compression-threshold=1048576` 时：

```text
Raw: 189.06 GB (3.6MB/s) | Zstd: 10.28 GB (252.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (5.0MB/s) | Zstd: 10.28 GB (234.3KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (3.2MB/s) | Zstd: 10.28 GB (215.5KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.07 GB (4.8MB/s) | Zstd: 10.28 GB (303.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.07 GB (4.3MB/s) | Zstd: 10.28 GB (477.2KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.08 GB (3.3MB/s) | Zstd: 10.28 GB (319.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.08 GB (3.5MB/s) | Zstd: 10.28 GB (154.1KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.08 GB (4.2MB/s) | Zstd: 10.28 GB (353.0KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.09 GB (2.9MB/s) | Zstd: 10.28 GB (49.8KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.09 GB (2.8MB/s) | Zstd: 10.28 GB (324.2KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.09 GB (3.3MB/s) | Zstd: 10.28 GB (137.8KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.10 GB (2.9MB/s) | Zstd: 10.28 GB (201.2KB/s) | Ratio: 5.44% | Conns: 8
```

## 使用方式

### 客户端

1. 安装模组并启动游戏。
2. 在“多人游戏”或“直接连接”中输入远程 `zstd` 地址，例如 `example.com:35565`。
3. 点击加入后，模组会自动启动本地代理并接管连接。

### 服务端

首次启动后，会在 `config` 目录生成 `zstdnet-server.properties`。

- `listen` 作为公网 `zstd` 入口
- `target` 指向后端 Minecraft 或代理服务
- 玩家连 `listen`
- 模组把流量解压/压缩后转发给 `target`

常用配置项如下：

| 配置项 | 含义 |
| --- | --- |
| `enabled=false` | 是否启用内置 zstd 服务端代理 |
| `listen=0.0.0.0:35565` | zstd 公网监听地址 |
| `target=127.0.0.1:25565` | 后端 Minecraft / Velocity 地址 |
| `level=9` | 服务端到客户端方向的 zstd 压缩级别 |
| `max_conn_per_ip=20` | 每个源 IP 最大并发连接数 |
| `max_req_per_window=30` | 每个源 IP 在窗口期内最大请求次数 |
| `request_window=10s` | 请求计数窗口 |
| `ban_duration=30m` | 超限后的封禁时长 |
| `stats_interval=1s` | 统计日志输出间隔 |
| `flush_interval=2ms` | zstd flush 间隔 |
| `idle_timeout=25s` | 空闲读超时 |
| `max_rate_per_conn_bps=0` | 单连接限速，0 为关闭 |
| `max_rate_global_bps=0` | 全局总限速，0 为关闭 |
| `burst_bytes=262144` | 令牌桶突发容量 |

如果你前面还有 `FRP`、`HAProxy` 一类转发层，建议开启 `PROXY protocol v2`，否则源 IP 识别可能不准确，进而影响限流和封禁逻辑。

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
- 原版状态查询直接透传，方便列表 ping
- 原版登录直接断开并返回提示
- zstd 流量正常转发到后端 `target`
- 输出 `Raw / Zstd / Ratio / Conns` 等统计信息


### 常见问题

- 客户端是通过界面 hook 接管连接，如果多人游戏菜单被其他模组大幅改写，理论上有冲突风险
- 在线模式下登录后链路会被加密，压缩收益通常会明显下降
- 如果 `Ratio` 接近 `100%`，不一定是没生效，也可能只是数据本身不适合继续压缩

## 配置文件

- 客户端：`config/zstdnet-client.toml`
- 服务端：`config/zstdnet-server.properties`


## 第三方依赖说明

本项目依赖多个第三方组件，例如 Forge 与 `zstd-jni`。使用与分发时，请遵守它们各自的许可证条款。

> 本仓库协议仅覆盖本仓库自有代码，不替代第三方依赖许可证。

## 许可证

本项目采用 MIT 许可证，详情见 `LICENSE`。
