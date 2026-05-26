package cn.tohsaka.factory.zstdnet.core.stats;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class TrafficStats {
    public final AtomicLong rawBytes = new AtomicLong();
    public final AtomicLong zstdBytes = new AtomicLong();
    public final AtomicLong rawUpBytes = new AtomicLong();
    public final AtomicLong rawDownBytes = new AtomicLong();
    public final AtomicLong zstdUpBytes = new AtomicLong();
    public final AtomicLong zstdDownBytes = new AtomicLong();
    public final AtomicInteger activeConn = new AtomicInteger();

    public void addRaw(long bytes) {
        if (bytes > 0) {
            rawBytes.addAndGet(bytes);
        }
    }

    public void addRawUp(long bytes) {
        if (bytes > 0) {
            rawUpBytes.addAndGet(bytes);
            addRaw(bytes);
        }
    }

    public void addRawDown(long bytes) {
        if (bytes > 0) {
            rawDownBytes.addAndGet(bytes);
            addRaw(bytes);
        }
    }

    public void addZstd(long bytes) {
        if (bytes > 0) {
            zstdBytes.addAndGet(bytes);
        }
    }

    public void addZstdUp(long bytes) {
        if (bytes > 0) {
            zstdUpBytes.addAndGet(bytes);
            addZstd(bytes);
        }
    }

    public void addZstdDown(long bytes) {
        if (bytes > 0) {
            zstdDownBytes.addAndGet(bytes);
            addZstd(bytes);
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

    public long rawUpBytes() {
        return rawUpBytes.get();
    }

    public long rawDownBytes() {
        return rawDownBytes.get();
    }

    public long zstdUpBytes() {
        return zstdUpBytes.get();
    }

    public long zstdDownBytes() {
        return zstdDownBytes.get();
    }

    public int activeConnections() {
        return activeConn.get();
    }
}
