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
- The client-side local proxy also provides raw UDP passthrough on the same local port, for compatibility with mods such as Sable / Create Aeronautics that depend on Minecraft same-port UDP

## Real-World Results

The main purpose of this mod is to reduce public bandwidth usage caused by highly repetitive packet traffic.

In large modpack [齿轮盛宴官方网站](https://www.xn--dctt54dhmrbwo.com) environments, the compression gains can be very significant. Here is a real example of server-side stats:

```text
Raw: 189.06 GB (3.6MB/s) | Zstd: 10.28 GB (252.7KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (5.0MB/s) | Zstd: 10.28 GB (234.3KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.06 GB (3.2MB/s) | Zstd: 10.28 GB (215.5KB/s) | Ratio: 5.44% | Conns: 8
Raw: 189.07 GB (4.8MB/s) | Zstd: 10.28 GB (303.7KB/s) | Ratio: 5.44% | Conns: 8
```

**The lower the ratio, the less traffic is actually being transmitted after compression.**

## Installation

It is recommended to install this mod on both the client and the server.

- Current versions supported in this repository:
  Forge 1.20.1, NeoForge 1.20.1, NeoForge 1.21.1, Fabric 1.20.1, Fabric 1.21.1
- When connecting to a remote ZstdNet-enabled server: the client needs it
- When using the built-in Zstd server entry: the server needs it
- When opening a LAN world and sharing a Zstd entry externally: the host client needs it

## How Players Connect

If the server owner uses the default recommended `auto_takeover=true` setup, players usually keep using the same public address and port they already know. They do not need to learn a second port just for ZstdNet.

For example:

```text
play.example.com:25565
1.2.3.4:25565
```

In the default auto-takeover mode:

- Players keep connecting to the public port from `server.properties`
- ZstdNet automatically takes that port over
- The backend Minecraft server is moved to another local port automatically

Only when the server owner disables `auto_takeover` and switches to manual mode do players need to use a separate `listen` port, for example:

```properties
auto_takeover=false
listen=0.0.0.0:35565
target=127.0.0.1:25565
```

## Required Preparation

Before using the built-in ZstdNet server entry on a dedicated server, make sure the backend Minecraft server is configured correctly.

In your server's `server.properties`, at minimum set:

```properties
online-mode=false
```

- `online-mode=false`: disable vanilla online authentication on the backend server
- `network-compression-threshold=1048576`: when the built-in ZstdNet server entry is enabled on a dedicated server, the mod will take this over automatically at startup, so you usually do not need to fill it manually

If you keep vanilla authentication enabled on the backend server, connections may fail. If vanilla network compression is not taken over by ZstdNet, compression efficiency may also be much worse than expected.

If you still want premium account verification while keeping the backend in offline mode, you can additionally use [TrueUUID](https://www.curseforge.com/minecraft/mc-mods/trueuuid).

- This is useful for setups where the backend stays on `online-mode=false`, but you still want login-time premium account verification
- It can help preserve premium UUIDs, correct name casing, and skin-related profile data while running in offline mode
- This is especially practical for servers that need offline forwarding, NAT traversal, or proxy-style chains without fully giving up premium account checks

In short, ZstdNet handles compression and forwarding; if you also need premium account verification, you can pair it with a mod such as TrueUUID.

## Dedicated Server Setup

On first launch, the mod generates:

```text
config/zstdnet-server.properties
```

The most common configuration looks like this:

```properties
enabled=true
auto_takeover=true
```

Meaning:

- `auto_takeover=true`: read `server.properties` at startup and automatically use `server-port` as the public entry
- `listen` / `target`: resolved internally by ZstdNet in auto mode, so they usually do not need to be filled manually

In other words:

- Players still connect to the original public port
- ZstdNet takes that port over automatically
- The backend server is moved to another local port automatically
- ZstdNet decompresses/compresses traffic and forwards it internally

If you also use FRP, HAProxy, NAT traversal, or any other forwarding layer, make sure your public entry ultimately forwards to `listen`. Do not forward directly to the vanilla game port, or zstd will be bypassed.

If your modpack includes Sable / Create Aeronautics or any other mod that depends on same-port UDP, make sure the forwarding layer also forwards both TCP and UDP on the same port. ZstdNet listens on both TCP and UDP at the client-side local address `127.0.0.1:<local proxy port>` and forwards UDP packets unchanged to the same remote port; if your public tunnel only forwards TCP, Sable may fall back to its slower TCP pipeline.

With the default config, dedicated servers no longer need manual port planning. ZstdNet will read `server-port`, keep it as the public entry, and automatically move the backend to another free local port.

## Typical FRP Chain

Recommended chain:

```text
Player client -> Public FRP port -> Host ZstdNet listen port -> Local game port
```

For example:

- Public port: `25565`
- Backend game port: `25566` (auto-assigned if free)
- FRP public port: `25565`

Then the recommended setup is:

- Leave `auto_takeover=true` in `zstdnet-server.properties`
- Let ZstdNet keep `listen` on the configured public port
- Let ZstdNet move `target` to another local port automatically
- Configure `frpc.toml` to forward the public port to the host's public `listen`
- Players connect using the same public port they already know

## Real IP / FRP PROXY v2 Mode

If players connect directly to the ZstdNet entry port, such as public direct connection, LAN direct connection, or virtual LAN direct connection, keep:

```properties
trust_proxy_protocol=false
```

In this mode, ZstdNet reads the player's IP directly from the TCP connection. Public direct connections expose the player's public IP; LAN or virtual LAN direct connections expose the player's LAN / virtual-network IP.

Only enable this when there is an FRP / reverse-proxy layer in front of ZstdNet and you want the backend to see the player's real IP:

```properties
trust_proxy_protocol=true
trusted_proxy_ips=127.0.0.1,::1,0:0:0:0:0:0:0:1
```

The FRP TCP proxy must also send PROXY Protocol v2, for example:

```toml
transport.proxyProtocolVersion = "v2"
```

`trusted_proxy_ips` is the list of proxy machines that are allowed to tell ZstdNet the player's real IP. It is not a player IP allowlist and not a list of allowed network ranges. If frpc runs on the same machine as the server, keep the default localhost values. If frpc runs on another machine, put the LAN / virtual-network IP that machine uses to connect to this server.

Note: when `trust_proxy_protocol=true`, direct connections to the ZstdNet entry port without a PROXY v2 header will be rejected. This includes local, LAN, virtual LAN, and public direct connections to the `listen` port. This is intentional to prevent clients from spoofing real IP addresses.

## Singleplayer / LAN Hosting

When opening a singleplayer world to LAN, ZstdNet adds a Zstd port field to the "Open to LAN" screen and keeps the normal game port field compatible with vanilla or advanced LAN screens.

Recommended usage:

- Leave the game port empty unless you explicitly need a fixed Minecraft LAN port. ZstdNet will follow the actual LAN port used by this session.
- The Zstd port uses the configured port first. If that port is already occupied or reserved by the current LAN session, ZstdNet automatically falls back to another available port.
- After the LAN world is opened, the in-game chat prints the actual Zstd port. The Zstd port in that message can be clicked to copy it.

The active settings are stored in:

```text
config/zstdnet-server.properties
```

and support hot reload.

If friends are joining from outside your local network, give them this address:

```text
Your public IP or domain:Zstd port
```

For example:

```text
mc.example.com:35565
203.0.113.10:35565
```

If a LAN UI mod fully replaces the screen and the Zstd field is not visible, use `/zstdport show` to view the current ports, or `/zstdport zstd <port>` only when you need to pin a fixed public/tunnel port.

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
/zstdport voice 25565
/zstdport zstdvoice 24455
```

Notes:

- `/zstdport` is a client-side command
- `show` can view the current config
- `voice` changes `voice_chat_target`, which is the backend voice port
- `zstdvoice` changes `voice_chat_listen`, which is the public voice entry
- In singleplayer/LAN hosting, the default `voice_chat_target` is `127.0.0.1:25565`
- On dedicated servers, the default `voice_chat_target` remains `127.0.0.1:24454`
- `game`, `zstd`, `voice`, and `zstdvoice` can only be changed by the local host with admin permission
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

1. If `auto_takeover=true`, players should keep using the usual public port from `server.properties`. If `auto_takeover=false`, make sure players are using the configured `listen` port.
2. Make sure FRP or other tunnels are forwarding to the `listen` port.
3. Make sure `enabled=true` is set in `config/zstdnet-server.properties`.
4. Make sure `listen` and `target` are not reversed.
5. Make sure the Zstd port is not already in use by another program.
6. Make sure no other mod is intercepting the login or handshake flow.
7. In LAN setups, use the Zstd port shown in chat. The game port can usually be left empty because ZstdNet follows the actual LAN session port automatically.

### Does a ratio near 100% mean it's not working?

Not necessarily.

Some traffic simply does not compress well, or may already be encrypted, which reduces the benefit significantly.

### Can this conflict with other multiplayer mods?

Possibly.

UI-heavy menu rewrites can still cause compatibility issues.

### What about Simple Voice Chat or other UDP-based voice mods?

Simple Voice Chat audio is not compressed by zstd. ZstdNet only forwards the UDP packets as raw passthrough on the server side.

- If Simple Voice Chat uses its own UDP port, you must set `voice_chat_listen` explicitly. `voice_chat_target` can stay blank and will default to the local SVC port.
- If the UDP route cannot be armed, the game TCP path still works, but voice chat may stay offline.
- If you want to edit the `voice` passthrough entries in `zstdnet-server.properties` directly, use `/zstdport voice <port>` for the backend voice port, or `/zstdport zstdvoice <port>` for the public voice entry.

## Configuration Files

- Client: `config/zstdnet-client.toml`
- Server: `config/zstdnet-server.properties`
- `zstdnet-server.properties` is auto-maintained. When ports are changed by commands or auto takeover, the file is rewritten with the built-in commented template.

**Configuration Item Explanation :**

- `enabled`：Whether to enable ZstdNet service (default: true)
  - Set to true to use Zstd compression

- `auto_takeover`：Whether to automatically use the `server-port` from `server.properties` as the public entry (default: true)
  - When enabled, server owners usually do not need to fill ports manually
  - ZstdNet will move the backend Minecraft server to another free local port during startup

- Dedicated auto mode：The config usually does not need fixed `listen` / `target` values
  - `listen` is resolved to the current public entry during startup
  - `target` is resolved to an automatically assigned local backend port during startup
  - Do not expose the runtime `target` directly to the internet

- `listen`：Zstd compressed entry address and port in manual mode
  - When `auto_takeover=false`, this is the address and port players should use
  - `0.0.0.0` means allow access from all IPs

- `target`：Backend Minecraft server address and port in manual mode
  - ZstdNet forwards compressed traffic to this address
  - `127.0.0.1` means local server

- `level`：Compression strength (1-22, recommended 3-9, default: 9)
  - Higher numbers mean better compression but use more CPU
  - Usually 3-5 is enough for a good balance of performance and compression

- `max_conn_per_ip`：Maximum simultaneous connections per IP (default: 9999)
  - Set to 0 or negative to disable limit
  - Prevents a single IP from using too many connections

- `max_req_per_window`：Maximum requests per IP within a time window (default: 50)
  - Set to 0 or negative to disable limit
  - Prevents malicious request spamming

- `request_window`：Time range for request counting (default: 10s)
  - Works with max_req_per_window, e.g., max 50 requests within 10 seconds

- `ban_duration`：Ban duration after exceeding limits (default: 1m)
  - Prevents malicious attacks, banned IPs can't connect temporarily

- `stats_interval`：Interval for server logs to show traffic statistics (default: 0s, disables stats logs)
  - When set to a positive interval, periodically shows traffic information in the console

- `flush_interval`：How often to send compressed data (default: 2ms)
  - Set to 0 to send immediately after each compression
  - Smaller values mean lower latency but may increase network overhead

- `idle_timeout`：Backend connection idle timeout (default: 0)
  - Set to 0 to keep connections active indefinitely
  - Non-zero value means automatically close connections that are idle for longer than this time

- `max_rate_per_conn_bps`：Maximum speed limit per connection (default: 0)
  - Unit is bytes/second, set to 0 to disable limit
  - Prevents a single connection from using too much bandwidth

- `max_rate_global_bps`：Total speed limit for all connections (default: 0)
  - Unit is bytes/second, set to 0 to disable limit
  - Controls overall bandwidth usage

- `burst_bytes`：Allowed burst traffic size (default: 262144)
  - Unit is bytes, acts as a "buffer pool" for traffic
  - Allows short-term burst traffic to exceed the limit even with rate limiting enabled

- `voice_chat_passthrough`：Enable raw UDP passthrough for Simple Voice Chat (default: true)
  - Voice traffic is forwarded without zstd compression
  - Set to false to disable voice UDP route handling entirely

- `voice_chat_listen`：Optional public UDP entry for voice chat
  - In singleplayer/LAN mode, the generated default is usually `0.0.0.0:24455`
  - In separate-port mode, this must be set explicitly

- `voice_chat_target`：Optional backend UDP target for voice chat
  - In singleplayer/LAN mode, the generated default is `127.0.0.1:25565`
  - On dedicated servers, the generated default is `127.0.0.1:24454`
  - In separate-port mode, when `voice_chat_listen` is set, it defaults to `127.0.0.1:<SVC port>`

### Client Configuration File `zstdnet-client.toml` Content

```toml
# Configuration file

[general]
	# zstd compression level for client->server stream
	level = 3
```

**Configuration Item Explanation:**

- `level`：Zstd compression level for client->server stream (default: 3, range: 1-22)
  - Higher levels provide better compression but increase CPU usage
  - It is recommended to choose between 3-5 to balance compression effect and performance

## Dependencies

- zstd-jni

## License

This project is licensed under the MIT License.
