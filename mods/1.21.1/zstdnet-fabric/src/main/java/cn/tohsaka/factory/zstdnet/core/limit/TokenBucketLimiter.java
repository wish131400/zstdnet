package cn.tohsaka.factory.zstdnet.core.limit;

public final class TokenBucketLimiter {
    private final double rateBytesPerSecond;
    private final double capacity;
    private double tokens;
    private long lastNanos;

    private TokenBucketLimiter(long rateBytesPerSecond, long burstBytes) {
        this.rateBytesPerSecond = Math.max(1L, rateBytesPerSecond);
        this.capacity = Math.max(1L, burstBytes);
        this.tokens = this.capacity;
        this.lastNanos = System.nanoTime();
    }

    public static TokenBucketLimiter create(long rateBytesPerSecond, long burstBytes) {
        if (rateBytesPerSecond <= 0) {
            return null;
        }
        long burst = burstBytes > 0 ? burstBytes : rateBytesPerSecond;
        return new TokenBucketLimiter(rateBytesPerSecond, burst);
    }

    public void waitBytes(int bytes) {
        if (bytes <= 0) {
            return;
        }

        double remaining = bytes;
        while (remaining > 0) {
            double chunk = Math.min(remaining, capacity);
            waitChunk(chunk);
            remaining -= chunk;
        }
    }

    private void waitChunk(double need) {
        while (true) {
            long sleepNanos;
            synchronized (this) {
                long now = System.nanoTime();
                double elapsedSeconds = (now - lastNanos) / 1_000_000_000.0D;
                if (elapsedSeconds > 0) {
                    tokens = Math.min(capacity, tokens + elapsedSeconds * rateBytesPerSecond);
                }
                lastNanos = now;

                if (tokens >= need) {
                    tokens -= need;
                    return;
                }

                double shortage = need - tokens;
                sleepNanos = (long) Math.ceil((shortage / rateBytesPerSecond) * 1_000_000_000.0D);
            }

            if (sleepNanos <= 0) {
                continue;
            }

            long sleepMillis = sleepNanos / 1_000_000L;
            int nanosPart = (int) (sleepNanos % 1_000_000L);
            try {
                Thread.sleep(sleepMillis, nanosPart);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
