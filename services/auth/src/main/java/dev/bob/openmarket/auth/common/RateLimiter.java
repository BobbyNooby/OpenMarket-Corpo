package dev.bob.openmarket.auth.common;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal fixed-window rate limiter (in-memory, per-instance). Good enough
 * to protect email-sending endpoints from obvious abuse; the gateway owns
 * the real fleet-wide limiting later. Buckets are lazily evicted when a new
 * window starts for the same key.
 */
@Service
public class RateLimiter {

    private record Window(long startEpochSecond, AtomicInteger count) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** @throws RateLimitException when the call exceeds limit per window. */
    public void allow(String name, String identity, int limit, Duration window) {
        long windowSeconds = window.toSeconds();
        long now = Instant.now().getEpochSecond();
        String key = name + ":" + identity;
        long bucket = now / windowSeconds;

        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.startEpochSecond() != bucket) {
                return new Window(bucket, new AtomicInteger(0));
            }
            return existing;
        });

        int used = w.count().incrementAndGet();
        if (used > limit) {
            long windowEnd = (bucket + 1) * windowSeconds;
            throw new RateLimitException(Math.max(1, windowEnd - now));
        }
    }
}
