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

If you still want premium account verification while keeping the backend in offline mode, you can additionally use [TrueUUID](https://www.mcmod.cn/class/21953.html).

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

1. If `auto_takeover=true`, players should keep using the usual public port from `server.properties`. If `auto_takeover=false`, make sure players are using the configured `listen` port.
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

## Configuration Files

- Client: `config/zstdnet-client.toml`
- Server: `config/zstdnet-server.properties`

### Server Configuration File `zstdnet-server.properties` Content

```properties
# ------------------------------------------------------------
# zstdnet built-in server configuration (auto-generated)
# ------------------------------------------------------------
# 1) Dedicated servers should usually keep auto_takeover=true.
# 2) With auto_takeover=true, the public entry follows server-port in server.properties.
# 3) By default only vanilla status ping is passed through, not vanilla login.
# 4) listen / target only need to be added in advanced manual mode.
# 5) Do not write the address as 127.0.0.1. (trailing dot will fail to parse).

# Whether to enable built-in zstd proxy.
enabled=true

# Whether to automatically take over the `server-port` from server.properties.
auto_takeover=true

# To switch back to manual mode, add:
# listen=0.0.0.0:25565
# target=127.0.0.1:25566

# zstd compression level (1-22, usually recommended 3-9).
level=9

# Maximum concurrent connections per IP (<=0 means disable limit).
max_conn_per_ip=9999

# Maximum requests per IP within request_window (<=0 means disable limit).
max_req_per_window=50

# Request counting time window.
request_window=10s

# Ban duration after exceeding limits.
ban_duration=1m

# Statistics log output interval.
stats_interval=1s

# zstd flush interval, 0ms means flush on every write.
flush_interval=2ms

# idle read timeout for backend reads, 0 means disabled.
idle_timeout=0

# Per-connection rate limit (bytes/second, 0 means disabled).
max_rate_per_conn_bps=0

# Global total rate limit (bytes/second, 0 means disabled).
max_rate_global_bps=0

# Token bucket burst capacity (bytes).
burst_bytes=262144
```

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

- `stats_interval`：Interval for server logs to show traffic statistics (default: 1s)
  - Shows traffic information in the console every 1 second

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

- Minecraft Forge
- zstd-jni

## License

This project is licensed under the MIT License.
