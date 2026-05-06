package cn.tohsaka.factory.zstdnet.core.stats;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TrafficStats {
    public final AtomicLong rawBytes = new AtomicLong();
    public final AtomicLong zstdBytes = new AtomicLong();
    public final AtomicInteger activeConn = new AtomicInteger();

    public void addRaw(long bytes) {
        if (bytes > 0) {
            rawBytes.addAndGet(bytes);
        }
    }

    public void addZstd(long bytes) {
        if (bytes > 0) {
            zstdBytes.addAndGet(bytes);
        }
    }

    public void addConn(int delta) {
        activeConn.addAndGet(delta);
    }

    public long rawBytes() {
        return rawBytes.get();
    }

    public long zstdBytes() {
        return zstdBytes.get();
    }

    public int activeConnections() {
        return activeConn.get();
    }
}
