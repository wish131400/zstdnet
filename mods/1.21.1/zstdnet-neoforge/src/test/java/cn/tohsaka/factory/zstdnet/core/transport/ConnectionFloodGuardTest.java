package cn.tohsaka.factory.zstdnet.core.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionFloodGuardTest {
    @Test
    void limitsConcurrentConnectionsPerIpAndReleasesOnClose() {
        ConnectionFloodGuard guard = new ConnectionFloodGuard(2, 0, 10_000L, 60_000L);
        assertTrue(guard.begin("192.0.2.1", 1_000L));
        assertTrue(guard.begin("192.0.2.1", 1_001L));
        assertFalse(guard.begin("192.0.2.1", 1_002L));
        assertTrue(guard.begin("192.0.2.2", 1_002L));
        guard.end("192.0.2.1", 1_003L);
        assertTrue(guard.begin("192.0.2.1", 1_004L));
    }

    @Test
    void bansRepeatedRequestsThenExpiresAfterWindowAndBan() {
        ConnectionFloodGuard guard = new ConnectionFloodGuard(0, 2, 10_000L, 60_000L);
        assertTrue(guard.begin("192.0.2.1", 1_000L));
        guard.end("192.0.2.1", 1_001L);
        assertTrue(guard.begin("192.0.2.1", 1_002L));
        guard.end("192.0.2.1", 1_003L);
        assertFalse(guard.begin("192.0.2.1", 1_004L));
        assertFalse(guard.begin("192.0.2.1", 61_003L));
        assertTrue(guard.begin("192.0.2.1", 61_004L));
    }
}
