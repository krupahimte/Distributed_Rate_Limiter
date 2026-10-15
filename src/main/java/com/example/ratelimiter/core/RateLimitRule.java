package com.example.ratelimiter.core;

import com.example.ratelimiter.annotation.RateLimit;

import java.time.Duration;

/**
 * Algorithm-independent description of a limit: {@code refillRate} tokens every
 * {@code refillPeriod}, with bursts of up to {@code capacity}.
 */
public record RateLimitRule(long capacity, long refillRate, Duration refillPeriod) {

    public RateLimitRule {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, was " + capacity);
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0, was " + refillRate);
        }
        if (refillPeriod == null || refillPeriod.isNegative() || refillPeriod.isZero()) {
            throw new IllegalArgumentException("refillPeriod must be positive, was " + refillPeriod);
        }
    }

    public static RateLimitRule from(RateLimit annotation) {
        return new RateLimitRule(
                annotation.capacity(),
                annotation.refillRate(),
                Duration.of(annotation.refillPeriod(), annotation.unit()));
    }

    /** Token bucket: tokens restored per millisecond. */
    public double tokensPerMillisecond() {
        return (double) refillRate / refillPeriod.toMillis();
    }

    /**
     * Window algorithms: the window in which {@code capacity} requests are allowed, chosen so
     * the sustained rate matches the token bucket. At least 1 ms.
     */
    public long windowMillis() {
        return Math.max(1, Math.round(capacity * (double) refillPeriod.toMillis() / refillRate));
    }
}
