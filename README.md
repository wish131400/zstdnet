# ZstdNet 文档

- English: [README.en-US.md](README.en-US.md)
- 中文: [README.zh-CN.md](README.zh-CN.md)
- (本项目自V1.3.8版本不在发布新版本jar在github，请前往curseforge下载)
- (Starting from version V1.3.8, this project will no longer release new version JAR files on GitHub. Please go to CurseForge to download.)

## 省流版

### 当前支持版本

- Forge 1.20.1
- NeoForge 1.20.1
- NeoForge 1.21.1
- Fabric 1.20.1
- Fabric 1.21.1

## Velocity版本

https://github.com/wish131400/zstdnet-Velocity
VC版本因为兼容性原因，需要下载velocity兼容版本，trueuuid也需要下载velocity兼容版本。

### 客户端联机配置

安装好mod后，直接打开局域网即可。游戏端口通常可以留空，ZstdNet 会自动跟随本次实际 LAN 端口；Zstd 端口会优先使用配置里的端口，如果被占用会自动换到可用端口。开放成功后聊天框会提示实际 Zstd 端口，并且端口可以点击复制。如果使用高级联机 mod 完全替换界面导致看不到 Zstd UI，可以用 `/zstdport show` 查看当前端口；只有需要固定公网/隧道端口时，才用 `/zstdport zstd xxxxx` 手动指定。
有正版验证需求，可以额外搭配 [TrueUUID（正版离线共存）](https://www.mcmod.cn/class/21953.html)。使用/zstdhud on指令可以查看压缩状态。

### 服务器配置

安装好mod后，仅需要在server.properties里面关闭正版验证online-mode=false即可体验带宽压缩。
有正版验证需求，可以额外搭配 [TrueUUID（正版离线共存）](https://www.mcmod.cn/class/21953.html)。使用/zstdhud on指令可以查看压缩状态。

# curseforge

- https://www.curseforge.com/minecraft/mc-mods/zstdnet

# 鸣谢
- [齿轮盛宴官方网站]( https://www.xn--dctt54dhmrbwo.com/ )
- [量子科技官方网站]( https://www.mcplay.cc/ )
- [本项目灵感来源]( https://github.com/MeguminKato )
