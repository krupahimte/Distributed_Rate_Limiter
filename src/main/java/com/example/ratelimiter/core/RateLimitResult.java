package com.example.ratelimiter.core;

/**
 * Outcome of a single rate limit check; maps directly to the X-RateLimit-* response headers.
 *
 * @param allowed          whether the request may proceed
 * @param limit            the configured capacity
 * @param remaining        requests still available right now (never negative)
 * @param retryAfterMillis when denied, how long until a request could succeed; 0 when allowed
 */
public record RateLimitResult(boolean allowed, long limit, long remaining, long retryAfterMillis) {

    public RateLimitResult {
        remaining = Math.max(0, remaining);
        retryAfterMillis = Math.max(0, retryAfterMillis);
    }

    public static RateLimitResult allowed(long limit, long remaining) {
        return new RateLimitResult(true, limit, remaining, 0);
    }

    public static RateLimitResult denied(long limit, long retryAfterMillis) {
        return new RateLimitResult(false, limit, 0, retryAfterMillis);
    }

    /** Value for the Retry-After header: whole seconds, rounded up, at least 1 when denied. */
    public long retryAfterSeconds() {
        if (allowed) {
            return 0;
        }
        return Math.max(1, (retryAfterMillis + 999) / 1000);
    }
}
