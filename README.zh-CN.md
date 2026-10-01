# ZstdNet

ZstdNet 是 Minecraft Java 版的网络压缩模组。客户端和服务端在登录后协商 Zstd，对游戏连接内的数据帧压缩。高重复数据场景下，线路流量通常会明显低于原始数据量。

## 安装与连接

支持 Forge 1.20.1、NeoForge 1.20.1、Fabric 1.20.1、NeoForge 1.21.1 和 Fabric 1.21.1。每个 Minecraft 版本与加载器使用各自的 JAR，不能用一个 JAR 跨版本。

客户端和服务端安装对应版本后，玩家直接连接 `server.properties` 的 `server-port`。开启 `online-mode=true` 时，正版验证和连接加密仍由 Minecraft 原版处理；`online-mode=false` 时也能协商同一套压缩，但不提供正版验证。没有安装 ZstdNet 的客户端不会被强制切换到 Zstd。

单机开放局域网时，房主和朋友都安装模组，朋友连接本次开放的实际游戏端口。不再需要独立的 Zstd 入口端口。FRP 或端口映射也转发到原游戏端口。

确认客户端支持传输通道后，服务端会在首个初始游戏同步包发送前开始协商。初始游戏数据等待激活，最多等待 5 秒或保留 4096 个包；超限会恢复原版发送，避免大批初始同步数据先进入原版发送队列并堵住激活包。LOGIN 与 CONFIGURATION 阶段仍使用原版压缩，请保持压缩阈值为 256。较晚才声明通道的客户端会通过正常的加入游戏回调协商。

小包在请求发送后使用固定 2 ms 合并窗口，多个游戏包共用一次压缩刷新和一个 12 B 封装头。普通批次最多 64 KiB；满批、单个大包和关闭连接时立即刷新。该优化在正版与离线连接中均生效，无需新增配置，封装协议与此前内置版本兼容。孤立的小包仍有封装开销，短时线路流量可能高于原始流量。

## 服务器配置

首次启动生成 `config/zstdnet-server.properties`。常用配置如下：

```properties
enabled=true
level=9
max_conn_per_ip=64
max_req_per_window=50
request_window=10s
ban_duration=1m
trust_proxy_protocol=false
trusted_proxy_ips=127.0.0.1,::1,0:0:0:0:0:0:1
debug=false
```

`debug` 控制 PROXY v2 真实 IP 转发成功日志，默认 `false`。需要排查代理连接时设为 `true`，修改后重启服务端生效。升级时会自动补上该字段并保留现有配置值。

`enabled` 控制压缩协商。服务端的 `level` 控制服务端到客户端的 Zstd 压缩等级，范围 1–22，默认 9；等级越高通常压得更小，但更耗 CPU。客户端到服务端的等级仍由客户端配置中的 `level` 控制，默认 3。四项限流设置用于控制单 IP 并发连接和一段时间内的连接请求；数值限制 `<=0` 表示不限制。推荐将 `server.properties` 中的 `network-compression-threshold` 保持为原版默认值 `256`，让登录和协商前的数据仍经过原版压缩。Zstd 激活后会按连接移除原版 zlib，不需要提前抬高阈值。

旧外置代理架构已移除。`legacy_proxy`、`auto_takeover`、`listen`、`target`、`udp_direct_ports` 及旧代理的 flush、空闲超时与带宽配置不再生效，配置整理时会删除。升级前若曾使用独立代理端口，请将公网 TCP 映射改为 `server-port`；客户端同样使用原游戏地址。旧客户端配置中的 `legacy_proxy` 已不再读取。

## UDP 与真实 IP

ZstdNet 不压缩 UDP，也不接管 UDP 端口。语音或其他 UDP 模组仍按该模组的设置开放端口；若模组与游戏共用端口，则在前置隧道同时转发该端口的 TCP 和 UDP。无需在 ZstdNet 配置中登记 UDP 端口。

公网、局域网或虚拟局域网直连时，保持 `trust_proxy_protocol=false`，服务端会读取 TCP 对端 IP。通过 FRP 或反代转发并需要保留玩家真实 IP 时，前置代理须发送 PROXY Protocol v2，再设置：

```properties
trust_proxy_protocol=true
trusted_proxy_ips=127.0.0.1,::1,0:0:0:0:0:0:1
```

`trusted_proxy_ips` 填前置代理连接本服务器时使用的 IP，而非玩家 IP。代理在另一台机器时请填其内网 IP。开启 PROXY v2 后，非可信来源的直连请求必须带有效 PROXY v2 头。上述设置在开启和关闭正版验证时均有效。

## HUD 与流量报告

在客户端执行 `/zstdhud on` 显示服务器游戏端口、线路与原始流量、压缩率、连接数；`/zstdhud off` 关闭，`/zstdhud toggle` 切换。HUD 不区分旧代理模式。压缩率为线路总字节数除以原始总字节数，数字越低代表节省越多；短时间内线路开销可能使比例高于 100%。

服务端在 `config/zstdnet/stats/` 保存流量统计。拥有 2 级命令权限的玩家可在已安装模组的客户端执行：

```text
/zstdreport today
/zstdreport session
/zstdreport 24h
/zstdreport 7d
/zstdreport 30d
```

客户端会在 `.minecraft/config/zstdnet/reports/` 生成自包含 HTML，并在聊天栏提供打开链接。报告支持时间范围筛选、深浅模式和带宽参考。
