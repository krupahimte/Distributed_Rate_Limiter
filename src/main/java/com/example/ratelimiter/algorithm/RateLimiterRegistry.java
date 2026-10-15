package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimiter;

import java.util.EnumMap;
import java.util.Map;

/**
 * All available rate limiters by algorithm, plus the configured default. Lets an endpoint choose
 * its own algorithm with {@code @RateLimit(algorithm = ...)} while others use
 * {@code rate-limiter.algorithm}.
 */
public class RateLimiterRegistry {

    private final Map<Algorithm, RateLimiter> limiters;
    private final Algorithm defaultAlgorithm;

    public RateLimiterRegistry(Map<Algorithm, ? extends RateLimiter> limiters, Algorithm defaultAlgorithm) {
        if (!limiters.containsKey(defaultAlgorithm)) {
            throw new IllegalArgumentException("No RateLimiter for the default algorithm " + defaultAlgorithm);
        }
        this.limiters = new EnumMap<>(limiters);
        this.defaultAlgorithm = defaultAlgorithm;
    }

    /** The limiter for an endpoint: its {@code algorithm} override if set, otherwise the default. */
    public RateLimiter limiterFor(RateLimit rateLimit) {
        Algorithm[] override = rateLimit.algorithm();
        if (override.length > 1) {
            throw new IllegalStateException(
                    "@RateLimit accepts at most one algorithm, got " + override.length);
        }
        return override.length == 0 ? defaultLimiter() : limiterFor(override[0]);
    }

    public RateLimiter limiterFor(Algorithm algorithm) {
        RateLimiter limiter = limiters.get(algorithm);
        if (limiter == null) {
            throw new IllegalStateException("No RateLimiter registered for " + algorithm);
        }
        return limiter;
    }

    public RateLimiter defaultLimiter() {
        return limiters.get(defaultAlgorithm);
    }

    public Algorithm defaultAlgorithm() {
        return defaultAlgorithm;
    }
}
