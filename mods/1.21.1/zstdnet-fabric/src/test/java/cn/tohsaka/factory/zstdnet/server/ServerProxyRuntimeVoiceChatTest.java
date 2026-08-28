package cn.tohsaka.factory.zstdnet.server;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProxyRuntimeVoiceChatTest {

    @Test
    void directUdpPortsDefaultToTheSvcPort() {
        assertEquals(
            java.util.List.of(24454),
            ServerProxyRuntime.parseUdpDirectPorts("24454", 25565)
        );
    }

    @Test
    void directUdpPortsDeduplicateAndRejectTheGameRoute() {
        assertEquals(
            java.util.List.of(24454, 24456),
            ServerProxyRuntime.parseUdpDirectPorts("24454,25565,24454,24456", 25565)
        );
    }

    @Test
    void directUdpPortsIgnoreInvalidValues() {
        assertTrue(ServerProxyRuntime.parseUdpDirectPorts("0,-1,65536,abc", 25565).isEmpty());
    }

    @Test
    void directUdpPortsRejectTheResolvedGameRoute() {
        assertEquals(
            java.util.List.of(24454),
            ServerProxyRuntime.excludeGameUdpRoute(java.util.List.of(24454, 25566), 25566)
        );
    }

    @Test
    void hostPortParsesAndFormatsBracketedIpv6() {
        ServerProxyRuntime.HostPort endpoint = ServerProxyRuntime.HostPort.parse("[2001:db8::1]:25565");

        assertEquals(new ServerProxyRuntime.HostPort("2001:db8::1", 25565), endpoint);
        assertEquals("[2001:db8::1]:25565", endpoint.toString());
        assertEquals("2001:db8::1", ServerProxyConfigFile.parseHost("[2001:db8::1]:25565", "fallback"));
        assertEquals(25565, ServerProxyConfigFile.parsePort("[2001:db8::1]:25565", -1));
        assertEquals("[2001:db8::1]:25565", ServerProxyConfigFile.formatHostPort("2001:db8::1", 25565));
    }

    @Test
    void wildcardListenUsesAnyLocalBindAddress() {
        InetSocketAddress address = new ServerProxyRuntime.HostPort("0.0.0.0", 25565).toBindAddress();

        assertEquals(25565, address.getPort());
        assertTrue(address.getAddress().isAnyLocalAddress());
    }
}
