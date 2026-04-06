# 关于本项目

这是一个MC JAVA版本mod，基于ZSTD无损压缩算法，它可以有效降低机械动力类含有大量重复数据的服务器带宽

## 依赖库

本项目使用的依赖库：

- zstd-jni: <https://github.com/luben/zstd-jni>
- Minecraft Forge: <https://files.minecraftforge.net/>

## 压缩效果

本模组设计之初是为了降低机械动力类服务器具有大量重复的数据发包带宽，在整合包齿轮盛宴（[齿轮盛宴 - Minecraft机械动力整合包 | 官方网站](https://www.xn--dctt54dhmrbwo.com/)）中，多台官方服务器压缩效果极为明显，平均带宽降低约80-90%

### 实际效果示例（network-compression-threshold=1048576时）

```
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

## 第一次使用

在使用之前，请确保服务器的正版验证（online-mode=false）已经关闭，并且网络压缩阈值（network-compression-threshold=1048576）调整到合适数值

### 客户端（本模组为客户端服务端通用模组）

首次启动游戏后，会在整合包根目录自动生成 `servers.zstd.json`。

你需要手动填写 zstd 代理地址（不是 MC 服务端直连地址），保存后等待 `2~5` 秒，在游戏多人列表点击刷新即可看到配置的 zstd 线路。

### 服务端（本模组为客户端服务端通用模组）

首次启动后，会在 `config` 目录生成 `zstdnet-server.properties`。

常用配置项如下：

| 配置项                       | 含义                          |
| ------------------------- | --------------------------- |
| `enabled=false`           | 是否启用内置 zstd 服务端代理           |
| `listen=0.0.0.0:35565`    | zstd 客户端连接的公共监听地址           |
| `target=127.0.0.1:25565`  | 后端 Minecraft目标地址            |
| `level=9`                 | 后端到客户端方向的 zstd 压缩级别         |
| `max_conn_per_ip=20`      | 每个源 IP 最大并发连接数              |
| `max_req_per_window=30`   | 每个源 IP 在窗口期内最大请求次数          |
| `ban_duration=30m`        | 超限后封禁时长                     |
| `stats_interval=1s`       | 统计日志输出间隔                    |
| `flush_interval=2ms`      | zstd 刷新间隔（越小延迟越低，越大越平滑带宽峰值） |
| `max_rate_per_conn_bps=0` | 单连接限速（字节/秒，0 为关闭）           |
| `max_rate_global_bps=0`   | 全局总限速（字节/秒，0 为关闭）           |
| `burst_bytes=262144`      | 令牌桶突发容量（字节，越大越允许瞬时突发）       |

注:如果窗口期和连接数设置不当,在客户端大量刷新MOTD的情况下有可能会被封，如使用FRP,haproxy等转发软件的,请打开proxyprotocolv2协议，否则IP获取不正确可能导致封禁整条线路

## 工作原理

### 1. 客户端侧（Forge 模组）

- 启动后读取 `servers.zstd.json`
- 为每条线路启动本地 loopback 代理端口（`127.0.0.1:随机端口`）
- 将多人列表中的线路替换/追加为本地地址
- 游戏连接本地端口后：
  - 客户端 -> 远端：zstd 压缩
  - 远端 -> 客户端：zstd 解压并回灌给 Minecraft
    字段说明：
- `addr`：实际进入服务器时使用的目标地址，也就是登录/游戏流量走的地址。
- `mask`：用于热更新替换的稳定唯一标识。

`mode` 可选值：

- `auto`：优先按 `raw` 探测线路，失败后回退到 `zstd`
- `raw`：纯 Minecraft TCP 转发
- `zstd`（默认）：强制上下游使用 `zstd` 处理

### 2. 服务端侧（Forge 模组）

- 监听公网入口端口 `listen`
- 将流量转发到后端 MC/Velocity 端口 `target`
- 转发方向：
  - 客户端 -> 后端：zstd 解压
  - 后端 -> 客户端：zstd 压缩
- 提供基础防护：并发限制、请求速率窗口、封禁时长
- 输出实时统计：`Raw / Zstd / Ratio / Conns`

## 配置文件

- 客户端：`servers.zstd.json`、`config/zstdnet-client.toml`
- 服务端：`config/zstdnet-server.properties`

## 注意事项

- 在线模式（正版验证）下，登录后链路会加密，压缩收益通常偏低
- 此时 `Ratio` 接近 `100%` 甚至略高属于常见现象，不代表代理未工作

### 第三方依赖说明

本项目依赖多个第三方组件（如 Forge、`zstd-jni` 等），使用与分发时需遵守其各自许可证条款。

> 本仓库协议仅覆盖本仓库自有代码，不替代第三方依赖许可证。

## 许可证

该项目采用MIT许可证授权。详情请参见LICENSE。
