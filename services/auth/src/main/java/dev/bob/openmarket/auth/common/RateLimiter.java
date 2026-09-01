package dev.bob.openmarket.auth.common;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal fixed-window rate limiter (in-memory, per-instance). Good enough
 * to protect email-sending and login endpoints from obvious abuse; the
 * gateway owns the real fleet-wide limiting later. Buckets rotate lazily
 * when the same key starts a new window; a periodic sweep and a hard size
 * cap keep one-off identities from accumulating without bound.
 */
@Service
public class RateLimiter {

    private record Window(long startEpochSecond, long windowSeconds, AtomicInteger count) {
    }

    /** Hard ceiling on tracked buckets; over it, the oldest windows go first. */
    private static final int MAX_ENTRIES = 100_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxEntries;

    public RateLimiter() {
        this(MAX_ENTRIES);
    }

    /** Test seam: shrink the cap (and buckets) so eviction is observable. */
    RateLimiter(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    /** @throws RateLimitException when the call exceeds limit per window. */
    public void allow(String name, String identity, int limit, Duration window) {
        long windowSeconds = window.toSeconds();
        long now = Instant.now().getEpochSecond();
        String key = name + ":" + identity;
        long bucket = now / windowSeconds;

        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.startEpochSecond() != bucket) {
                return new Window(bucket, windowSeconds, new AtomicInteger(0));
            }
            return existing;
        });

        int used = w.count().incrementAndGet();
        if (used > limit) {
            long windowEnd = (bucket + 1) * windowSeconds;
            throw new RateLimitException(Math.max(1, windowEnd - now));
        }

        if (windows.size() > maxEntries) {
            evictUntilBounded();
        }
    }

    /**
     * Drops buckets whose window has fully elapsed. Lazy rotation only
     * reclaims a key when that SAME key is seen again, so without this
     * sweep every distinct one-off identity would live forever.
     */
    @Scheduled(fixedDelay = 300_000)
    public void evictElapsedWindows() {
        long now = Instant.now().getEpochSecond();
        windows.entrySet().removeIf(e ->
            now >= (e.getValue().startEpochSecond() + 1) * e.getValue().windowSeconds());
    }

    /** Last-resort flood guard: drop the oldest window starts until under the cap. */
    private void evictUntilBounded() {
        evictElapsedWindows(); // cheap first pass — most entries are usually stale
        while (windows.size() > maxEntries && evictOldestWindow()) {
            // keep going until bounded or nothing left to remove
        }
    }

    /** Removes every bucket with the oldest window start; false if the map is empty. */
    private boolean evictOldestWindow() {
        long oldest = Long.MAX_VALUE;
        for (Window w : windows.values()) {
            oldest = Math.min(oldest, w.startEpochSecond());
        }
        if (oldest == Long.MAX_VALUE) {
            return false;
        }
        final long cutoff = oldest;
        windows.values().removeIf(w -> w.startEpochSecond() == cutoff);
        return true;
    }

    /** Test/diagnostic seam: how many buckets are currently tracked. */
    int trackedKeyCount() {
        return windows.size();
    }
}
