package cn.tohsaka.factory.zstdnet.core.transport;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public final class ConnectionFloodGuard {
    private final int maxConnectionsPerIp;
    private final int maxRequestsPerWindow;
    private final long windowMillis;
    private final long banMillis;
    private final Map<String, Entry> entries = new HashMap<>();
    private long attempts;

    public ConnectionFloodGuard(int maxConnectionsPerIp, int maxRequestsPerWindow, long windowMillis, long banMillis) {
        this.maxConnectionsPerIp = maxConnectionsPerIp;
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowMillis = Math.max(0L, windowMillis);
        this.banMillis = Math.max(0L, banMillis);
    }

    public synchronized boolean begin(String ip) {
        return begin(ip, System.currentTimeMillis());
    }

    synchronized boolean begin(String ip, long now) {
        if ((attempts++ & 1023L) == 0L) {
            entries.entrySet().removeIf(item -> {
                Entry stale = item.getValue();
                prune(stale, now);
                return stale.active == 0 && stale.requests.isEmpty() && stale.bannedUntil <= now;
            });
        }
        Entry entry = entries.computeIfAbsent(ip, ignored -> new Entry());
        prune(entry, now);
        if (entry.bannedUntil > now) {
            return false;
        }
        if (maxConnectionsPerIp > 0 && entry.active >= maxConnectionsPerIp) {
            return false;
        }
        if (maxRequestsPerWindow > 0 && windowMillis > 0) {
            entry.requests.addLast(now);
            if (entry.requests.size() > maxRequestsPerWindow) {
                entry.bannedUntil = now + banMillis;
                return false;
            }
        }
        entry.active++;
        return true;
    }

    public synchronized void end(String ip) {
        end(ip, System.currentTimeMillis());
    }

    synchronized void end(String ip, long now) {
        Entry entry = entries.get(ip);
        if (entry == null) {
            return;
        }
        entry.active = Math.max(0, entry.active - 1);
        prune(entry, now);
        if (entry.active == 0 && entry.requests.isEmpty() && entry.bannedUntil <= now) {
            entries.remove(ip);
        }
    }

    private void prune(Entry entry, long now) {
        long cutoff = now - windowMillis;
        while (!entry.requests.isEmpty() && entry.requests.peekFirst() <= cutoff) {
            entry.requests.removeFirst();
        }
    }

    private static final class Entry {
        private int active;
        private long bannedUntil;
        private final Deque<Long> requests = new ArrayDeque<>();
    }
}
