package dev.bob.openmarket.auth.common;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    @Test
    void allows_up_to_the_limit_then_throws_429_with_retry_after() {
        RateLimiter limiter = new RateLimiter();

        for (int i = 0; i < 5; i++) {
            assertThatCode(() -> limiter.allow("forgot", "lux@demaciabook.com", 5, Duration.ofHours(1)))
                .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> limiter.allow("forgot", "lux@demaciabook.com", 5, Duration.ofHours(1)))
            .isInstanceOfSatisfying(RateLimitException.class, e -> {
                assertThat(e.code()).isEqualTo("rate_limited");
                assertThat(e.retryAfterSeconds()).isPositive();
            });
    }

    @Test
    void different_identities_have_independent_buckets() {
        RateLimiter limiter = new RateLimiter();

        limiter.allow("forgot", "a@x.dev", 1, Duration.ofHours(1));
        assertThatCode(() -> limiter.allow("forgot", "b@x.dev", 1, Duration.ofHours(1)))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.allow("forgot", "a@x.dev", 1, Duration.ofHours(1)))
            .isInstanceOf(RateLimitException.class);
    }

    @Test
    void different_names_have_independent_buckets() {
        RateLimiter limiter = new RateLimiter();

        limiter.allow("forgot", "k", 1, Duration.ofHours(1));
        assertThatCode(() -> limiter.allow("verify_resend", "k", 1, Duration.ofHours(1)))
            .doesNotThrowAnyException();
    }

    @Test
    void window_rotation_resets_the_counter() {
        RateLimiter limiter = new RateLimiter();
        AtomicInteger allowed = new AtomicInteger();

        // tiny window: 1-second buckets; hammer it and only the first per bucket passes
        for (int i = 0; i < 10; i++) {
            try {
                limiter.allow("forgot", "k", 1, Duration.ofSeconds(1));
                allowed.incrementAndGet();
            } catch (RateLimitException ignored) {
            }
            try {
                Thread.sleep(600); // eventually crosses a bucket boundary
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertThat(allowed.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void sweep_evicts_buckets_whose_window_has_fully_elapsed() throws InterruptedException {
        RateLimiter limiter = new RateLimiter();
        limiter.allow("forgot", "long-lived", 5, Duration.ofHours(1));    // still inside its window
        limiter.allow("forgot", "short-lived", 5, Duration.ofSeconds(1)); // elapses below

        Thread.sleep(1100);
        limiter.evictElapsedWindows();

        assertThat(limiter.trackedKeyCount()).isEqualTo(1);
    }

    @Test
    void sweep_keeps_buckets_still_inside_their_window() {
        RateLimiter limiter = new RateLimiter();
        limiter.allow("forgot", "k", 5, Duration.ofHours(1));

        limiter.evictElapsedWindows();

        assertThat(limiter.trackedKeyCount()).isEqualTo(1);
    }

    @Test
    void size_cap_evicts_oldest_buckets_on_insert() {
        RateLimiter limiter = new RateLimiter(2);
        limiter.allow("forgot", "a", 100, Duration.ofHours(1));
        limiter.allow("forgot", "b", 100, Duration.ofHours(1));
        limiter.allow("forgot", "c", 100, Duration.ofHours(1)); // third insert crosses the cap

        assertThat(limiter.trackedKeyCount()).isLessThanOrEqualTo(2);

        // a's counter was evicted with the flood, so its fresh bucket passes again
        assertThatCode(() -> limiter.allow("forgot", "a", 1, Duration.ofHours(1)))
            .doesNotThrowAnyException();
    }
}
