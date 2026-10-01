# ZstdNet

ZstdNet compresses Minecraft Java Edition game traffic with Zstd. The client and server negotiate compression after login, so players use the normal game address and port.

## Installation and Connection

Supported targets are Forge 1.20.1, NeoForge 1.20.1, Fabric 1.20.1, NeoForge 1.21.1, and Fabric 1.21.1. Each Minecraft version and loader requires its own JAR.

Install the matching mod on the client and server. Players connect to the `server-port` from `server.properties`. With `online-mode=true`, vanilla Minecraft still performs account verification and connection encryption. Offline servers can negotiate the same compression but do not gain account verification. Clients without ZstdNet are not forced to switch.

For a LAN world, both the host and joining players install the mod and use the actual game port shown when the world is opened. Public tunnels should forward to that same game port.

When the client's transport channel is known, negotiation starts before the first initial PLAY packet. Initial game packets wait for activation for at most 5 seconds or 4096 packets, then resume with vanilla compression if negotiation has not completed. This prevents a large initial synchronization burst from entering the vanilla socket queue ahead of activation. LOGIN and CONFIGURATION traffic still use vanilla compression; retain a compression threshold of 256. Clients whose channel is announced later can negotiate through the normal join callback.

Small packets share a fixed 2 ms batching window after a flush request, using one compression flush and one 12-byte header per batch. Normal batches are limited to 64 KiB; full batches, individual large packets, and connection closure flush immediately. This works with both online and offline connections without new configuration and preserves compatibility with the previous integrated wire format. Isolated small packets still carry framing overhead, so short wire samples may exceed raw traffic.

## Server Configuration

The first start creates `config/zstdnet-server.properties`:

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

`debug` controls successful PROXY v2 address-forwarding logs and defaults to `false`. Set it to `true` when diagnosing proxy connections, then restart the server. Upgrades add this field while preserving existing configuration values.

`enabled` controls compression negotiation. Server `level` controls Zstd compression from server to client (1–22, default 9); higher levels usually save more bandwidth at greater CPU cost. The client-to-server level remains in the client config (default 3). The four connection limits control concurrent connections and requests per IP. A numeric limit `<=0` disables that limit. Keep `network-compression-threshold` in `server.properties` at the vanilla default of `256` so login data is compressed before negotiation. Zstd activation removes vanilla zlib for that connection, so there is no need to raise the threshold beforehand.

The separate proxy architecture has been removed. `legacy_proxy`, `auto_takeover`, `listen`, `target`, `udp_direct_ports`, and the old proxy's flush, idle timeout, and bandwidth settings no longer apply. Config cleanup removes these fields. If an older deployment used a separate proxy port, update public TCP forwarding and client addresses to the original game port. Client `legacy_proxy` is no longer read.

## UDP and Real IP

ZstdNet does not compress or take over UDP. Open the ports required by voice chat or other UDP mods according to their own configuration. If a mod shares the game port for UDP, forward both TCP and UDP on that port. ZstdNet needs no UDP port list.

For direct public, LAN, or virtual LAN connections, leave `trust_proxy_protocol=false`. When FRP or a reverse proxy forwards players and must preserve their real IP, enable PROXY Protocol v2 on the proxy, then set:

```properties
trust_proxy_protocol=true
trusted_proxy_ips=127.0.0.1,::1,0:0:0:0:0:0:1
```

`trusted_proxy_ips` contains the proxy machine's address as seen by the server, not player addresses. Use its private IP if it runs on another machine. Nontrusted direct connections must supply a valid PROXY v2 header when this option is enabled. These settings work with both online and offline servers.

## HUD and Reports

Use `/zstdhud on` to display the game port, wire and raw traffic, compression ratio, and connection count. Use `/zstdhud off` or `/zstdhud toggle` to control it. Ratio is wire bytes divided by raw bytes; lower is better, while overhead can push a short sample above 100%.

The server stores statistics under `config/zstdnet/stats/`. Players with command permission level 2 can request a local HTML report from a modded client:

```text
/zstdreport today
/zstdreport session
/zstdreport 24h
/zstdreport 7d
/zstdreport 30d
```

The client saves self-contained reports under `.minecraft/config/zstdnet/reports/` and provides a link in chat. Reports include time filters, light and dark modes, and bandwidth reference values.
