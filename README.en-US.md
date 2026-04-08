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

## Configuration Files

- Client: `config/zstdnet-client.toml`
- Server: `config/zstdnet-server.properties`

### Server Configuration File `zstdnet-server.properties` Content

```properties
# ------------------------------------------------------------
# zstdnet built-in server configuration (auto-generated)
# ------------------------------------------------------------
# 1) First confirm listen / target, then set enabled to true.
# 2) listen and target cannot be the same endpoint.
# 3) Do not write the address as 127.0.0.1. (trailing dot will fail to parse).

# Whether to enable built-in zstd proxy.
enabled=true

# zstd public listening entry.
listen=0.0.0.0:35565

# Backend Minecraft
target=127.0.0.1:25565

# zstd compression level (1-22, usually recommended 3-9).
level=9

# Maximum concurrent connections per IP (<=0 means disable limit).
max_conn_per_ip=20

# Maximum requests per IP within request_window (<=0 means disable limit).
max_req_per_window=30

# Request counting time window.
request_window=10s

# Ban duration after exceeding limits.
ban_duration=30m

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

- `listen`：Zstd compression entry address and port (default: 0.0.0.0:35565)
  - This is the address and port players use to connect to the server
  - 0.0.0.0 means allow access from all IPs

- `target`：Backend Minecraft server address and port (default: 127.0.0.1:25565)
  - ZstdNet forwards compressed traffic to this address
  - 127.0.0.1 means local server

- `level`：Compression strength (1-22, recommended 3-9, default: 9)
  - Higher numbers mean better compression but use more CPU
  - Usually 3-5 is enough for a good balance of performance and compression

- `max_conn_per_ip`：Maximum simultaneous connections per IP (default: 20)
  - Set to 0 or negative to disable limit
  - Prevents a single IP from using too many connections

- `max_req_per_window`：Maximum requests per IP within a time window (default: 30)
  - Set to 0 or negative to disable limit
  - Prevents malicious request spamming

- `request_window`：Time range for request counting (default: 10s)
  - Works with max_req_per_window, e.g., max 30 requests within 10 seconds

- `ban_duration`：Ban duration after exceeding limits (default: 30m)
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
