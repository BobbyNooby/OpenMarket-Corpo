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
}
