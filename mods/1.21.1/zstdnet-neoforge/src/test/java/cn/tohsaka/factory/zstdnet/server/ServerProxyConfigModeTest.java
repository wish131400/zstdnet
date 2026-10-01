package cn.tohsaka.factory.zstdnet.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerProxyConfigModeTest {
    @TempDir
    Path tempDir;

    @Test
    void serverCompressionLevelDefaultsAndRejectsOutOfRangeValues() {
        assertEquals(9, ServerProxyConfigFile.parseLevel(null));
        assertEquals(9, ServerProxyConfigFile.parseLevel("invalid"));
        assertEquals(9, ServerProxyConfigFile.parseLevel("0"));
        assertEquals(9, ServerProxyConfigFile.parseLevel("23"));
        assertEquals(1, ServerProxyConfigFile.parseLevel("1"));
        assertEquals(22, ServerProxyConfigFile.parseLevel("22"));
    }

    @Test
    void invalidLimitDurationsUseDefaultsAndValidUnitsAreParsed() {
        assertEquals(10_000L, ServerProxyConfigFile.parseDurationMillis("invalid", 10_000L));
        assertEquals(10_000L, ServerProxyConfigFile.parseDurationMillis("999999999999999999999d", 10_000L));
        assertEquals(10_000L, ServerProxyConfigFile.parseDurationMillis("9223372036854775807d", 10_000L));
        assertEquals(250L, ServerProxyConfigFile.parseDurationMillis("250ms", 10_000L));
        assertEquals(120_000L, ServerProxyConfigFile.parseDurationMillis("2m", 10_000L));
    }

    @Test
    void retiredProxySettingsAreRemovedAndActiveSettingsRemain() throws IOException {
        Path config = tempDir.resolve("zstdnet-server.properties");
        Properties old = new Properties();
        old.setProperty("legacy_proxy", "true");
        old.setProperty("listen", "0.0.0.0:35565");
        old.setProperty("target", "127.0.0.1:35566");
        old.setProperty("udp_direct_ports", "24454");
        old.setProperty("level", "12");
        old.setProperty("max_conn_per_ip", "32");
        old.setProperty("trust_proxy_protocol", "true");
        old.setProperty("debug", "true");
        old.setProperty("custom_setting", "keep");
        ServerProxyConfigFile.writeConfigWithComments(config, old, "\n");

        String body = Files.readString(config);
        assertFalse(body.contains("legacy_proxy="));
        assertFalse(body.contains("listen="));
        assertFalse(body.contains("target="));
        assertFalse(body.contains("udp_direct_ports="));
        assertTrue(body.contains("max_conn_per_ip=32"));
        assertTrue(body.contains("level=12"));
        assertTrue(body.contains("trust_proxy_protocol=true"));
        assertTrue(body.contains("debug=true"));
        assertTrue(body.contains("custom_setting=keep"));
    }

    @Test
    void newConfigOnlyExposesActiveSettings() throws IOException {
        Path config = tempDir.resolve("new.properties");
        ServerProxyConfigFile.writeConfigWithComments(config, new Properties(), "\n");

        String body = Files.readString(config);
        assertTrue(body.contains("enabled=true"));
        assertTrue(body.contains("level=9"));
        assertTrue(body.contains("debug=false"));
        assertTrue(body.contains("max_req_per_window=50"));
        assertTrue(body.contains("request_window=10s"));
        assertTrue(body.contains("ban_duration=1m"));
        assertFalse(body.contains("auto_takeover="));
        assertFalse(body.contains("flush_interval="));
    }
}
