package dev.bob.openmarket.auth.common;

import java.time.Duration;

/** Thrown by the RateLimiter; maps to 429 + Retry-After. */
public class RateLimitException extends ApiException {

    private final long retryAfterSeconds;

    public RateLimitException(long retryAfterSeconds) {
        super("rate_limited", "Too many requests; slow down", null);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public org.springframework.http.HttpStatus status() {
        return org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
    }
}
