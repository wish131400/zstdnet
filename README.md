# ZstdNet 文档

- 中文说明：[README.zh-CN.md](README.zh-CN.md)
- English guide: [README.en-US.md](README.en-US.md)
- 自 V1.3.8 起，发布版 JAR 请从 [CurseForge](https://www.curseforge.com/minecraft/mc-mods/zstdnet) 下载。

## 支持版本

Forge 1.20.1、NeoForge 1.20.1、Fabric 1.20.1、NeoForge 1.21.1、Fabric 1.21.1 分别使用对应 JAR，同一 JAR 不能跨 Minecraft 版本或加载器使用。

## 使用方法

客户端和服务端安装对应版本模组后，玩家直接连接 `server.properties` 中的游戏端口。正版验证由原版 `online-mode=true` 负责；离线模式也可在登录后协商 Zstd 压缩。单机开放局域网同样使用本次实际游戏端口。

客户端通道已确认时，Zstd 协商会提前到初始游戏同步之前。初始数据最多等待 5 秒或保留 4096 个包，协商未完成时恢复原版发送。登录阶段仍使用原版压缩，建议保持 `network-compression-threshold=256`。

`config/zstdnet-server.properties` 可按需设置压缩开关、服务端压缩等级 `level`（默认 9）、连接限流以及 PROXY v2 真实 IP 转发。旧代理的 `legacy_proxy`、`auto_takeover`、`listen`、`target` 和 UDP 端口列表已移除；升级后请把公网映射指向原游戏端口。其他模组使用 UDP 时，仍需按该模组的说明开放或映射 UDP 端口。

`debug=false` 默认关闭 PROXY v2 转发成功日志。排查代理连接时可设为 `true`，修改后重启服务端生效。

在游戏内使用 `/zstdhud on` 查看线路流量、原始流量与压缩率。管理员可用 `/zstdreport today|session|24h|7d|30d` 生成本地流量报告。

Velocity 版本：[zstdnet-Velocity](https://github.com/wish131400/zstdnet-Velocity)。Spigot 移植：[ZstdNet-spigot](https://github.com/Meoyuta/ZstdNet-spigot)。
