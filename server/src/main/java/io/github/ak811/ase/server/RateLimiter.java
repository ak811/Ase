package io.github.ak811.ase.server;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-client token buckets: bursts up to the per-minute limit, refilled continuously. */
final class RateLimiter {
    private static final long IDLE_EVICTION_NANOS = 10L * 60 * 1_000_000_000L;
    private static final int CLEANUP_EVERY = 1024;
    private static final int MAX_CLIENTS = 100_000;

    private final double capacity;
    private final double tokensPerNano;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    RateLimiter(int requestsPerMinute) {
        this.capacity = requestsPerMinute;
        this.tokensPerNano = requestsPerMinute / 60e9;
    }

    boolean enabled() {
        return capacity > 0;
    }

    /** Takes one token; returns 0 if allowed, otherwise the seconds to wait. */
    long acquire(String client, long nowNanos) {
        if (!enabled()) {
            return 0;
        }
        if (calls.incrementAndGet() % CLEANUP_EVERY == 0 || buckets.size() > MAX_CLIENTS) {
            buckets.values().removeIf(bucket -> bucket.idleSince(nowNanos) > IDLE_EVICTION_NANOS);
        }
        Bucket bucket = buckets.computeIfAbsent(client, key -> new Bucket(capacity, nowNanos));
        return bucket.take(capacity, tokensPerNano, nowNanos);
    }

    private static final class Bucket {
        private double tokens;
        private long updatedNanos;

        Bucket(double tokens, long nowNanos) {
            this.tokens = tokens;
            this.updatedNanos = nowNanos;
        }

        synchronized long take(double capacity, double tokensPerNano, long nowNanos) {
            tokens = Math.min(capacity, tokens + (nowNanos - updatedNanos) * tokensPerNano);
            updatedNanos = nowNanos;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - tokens) / tokensPerNano / 1e9));
        }

        synchronized long idleSince(long nowNanos) {
            return nowNanos - updatedNanos;
        }
    }
}
