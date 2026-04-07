# ZstdNet

ZstdNet is a Minecraft Java Edition mod that uses ZSTD to compress relayed traffic between clients and servers, with the goal of significantly reducing public bandwidth usage in high-repetition data scenarios.

It is especially suitable for:

- Create-based servers
- Large modpack servers
- Multiplayer setups using FRP / NAT traversal / tunnel forwarding
- Singleplayer hosts who want to provide a more bandwidth-efficient external entry point for friends

## What This Mod Does

- When a client enters a ZstdNet address, the mod automatically starts a temporary local proxy and takes over the connection
- Provides a dedicated Zstd entry point on the server side and forwards compressed traffic to the backend Minecraft port
- Supports both dedicated servers and LAN worlds hosted from singleplayer
- Includes a HUD so you can check whether the connection is using zstd and view live traffic stats in-game
- Supports vanilla status ping passthrough so the server list can still query the server normally

## Real-World Results

The main purpose of this mod is to reduce public bandwidth usage caused by highly repetitive packet traffic.

In large modpack（ https://www.xn--dctt54dhmrbwo.com/ ） environments, the compression gains can be very significant. Here is a real example of server-side stats:

```text
Raw: 189.06 GB (3.6MB/s) | Zstd: 10.28 GB (252.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (5.0MB/s) | Zstd: 10.28 GB (234.3KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (3.2MB/s) | Zstd: 10.28 GB (215.5KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.07 GB (4.8MB/s) | Zstd: 10.28 GB (303.7KB/s) | Ratio: 5.44% | Conns: 8
```

**The lower the ratio, the less traffic is actually being transmitted after compression.**

## Installation

It is recommended to install this mod on both the client and the server.

- When connecting to a remote ZstdNet-enabled server: the client needs it
- When using the built-in Zstd server entry: the server needs it
- When opening a LAN world and sharing a Zstd entry externally: the host client needs it

## How Players Connect

If the server owner has already configured ZstdNet, players should join using the **Zstd entry address**, not the vanilla game port.

For example:

```text
play.example.com:35565
1.2.3.4:35565
```

If the server config is:

```properties
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

Then players should connect to **35565**, not **25565**.

## Required Preparation

Before using the built-in ZstdNet server entry on a dedicated server, make sure the backend Minecraft server is configured correctly.

In your server's `server.properties`, set:

```properties
online-mode=false
network-compression-threshold=1048576
```

- `online-mode=false`: disable vanilla online authentication on the backend server
- `network-compression-threshold=1048576`: effectively disables vanilla packet compression so ZstdNet can handle compression instead

If you keep vanilla authentication or vanilla network compression enabled on the backend server, connections may fail or compression efficiency may be much worse than expected.

## Dedicated Server Setup

On first launch, the mod generates:

```text
config/zstdnet-server.properties
```

The most common configuration looks like this:

```properties
enabled=true
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

Meaning:

- `listen`: the public compressed entry point provided by ZstdNet
- `target`: the actual backend Minecraft or proxy port

In other words:

- Players connect to `listen`
- ZstdNet decompresses/compresses traffic and forwards it to `target`

If you also use FRP, HAProxy, NAT traversal, or any other forwarding layer, make sure your public entry ultimately forwards to `listen`. Do not forward directly to the vanilla game port, or zstd will be bypassed.

## Typical FRP Chain

Recommended chain:

```text
Player client -> Public FRP port -> Host ZstdNet listen port -> Local game port
```

For example:

- Game port: `25565`
- ZstdNet port: `35565`
- FRP public port: `35565`

Then the recommended setup is:

- Set `listen` to `0.0.0.0:35565` in `zstdnet-server.properties`
- Set `target` to `127.0.0.1:25565`
- Configure `frpc.toml` to forward the public port to the host's `35565`
- Players connect using public port `35565`

## Singleplayer / LAN Hosting

ZstdNet adds two extra ports to the "Open to LAN" screen:

- Game Port
- Zstd Port

Recommended values:

- Use a normal port for the game port, such as `25565`
- Use another free port for the Zstd port, such as `35565`

After opening the world, the mod writes the settings to:

```text
config/zstdnet-server.properties
```

and supports hot reload.

If friends are joining from outside your local network, give them this address:

```text
Your public IP or domain:Zstd port
```

For example:

```text
mc.example.com:35565
203.0.113.10:35565
```

## Commands

### `/zstdhud`

Used to check or toggle the HUD:

```text
/zstdhud
/zstdhud on
/zstdhud off
/zstdhud toggle
```

### `/zstdport`

Used to view or change ports in singleplayer / LAN hosting scenarios:

```text
/zstdport show
/zstdport game 25565
/zstdport zstd 35565
```

Notes:

- `/zstdport` is a client-side command
- Only the local host with admin permission can change the ports
- This command does not modify dedicated server configs
- For dedicated servers, edit `config/zstdnet-server.properties` directly

## HUD Panel

After enabling `zstdhud`, you can view the following in-game:

- Current connection mode
- Listen address or remote target address
- Compressed real-time throughput
- Raw real-time throughput
- Total transferred traffic
- Compression ratio
- Current connection count

If you want to confirm whether the connection is actually using zstd, the HUD is the most direct way to check.

## FAQ

### Why can't I join the server?

Check these first:

1. Make sure players are using the `Zstd port`, not the vanilla game port.
2. Make sure FRP or other tunnels are forwarding to the `listen` port.
3. Make sure `enabled=true` is set in `config/zstdnet-server.properties`.
4. Make sure `listen` and `target` are not reversed.
5. Make sure the Zstd port is not already in use by another program.
6. Make sure no other mod is intercepting the login or handshake flow.
7. In LAN setups, make sure the game port matches the configured `target`.

### Does a ratio near 100% mean it's not working?

Not necessarily.

Some traffic simply does not compress well, or may already be encrypted, which reduces the benefit significantly.

### Can this conflict with other multiplayer mods?

Possibly.

On the client side, this mod works by hooking into the connection flow through the UI. If another mod heavily rewrites the multiplayer menu or the "Open to LAN" screen, compatibility issues are possible.

## Config Files

- Client: `config/zstdnet-client.toml`
- Server: `config/zstdnet-server.properties`

## Dependencies

- Minecraft Forge
- zstd-jni

## License

This project is licensed under the MIT License.
