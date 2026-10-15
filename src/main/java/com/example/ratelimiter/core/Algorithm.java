package com.example.ratelimiter.core;

/**
 * Rate limiting algorithms available behind the {@code RateLimiter} interface.
 * Selected globally via {@code rate-limiter.algorithm} in application.yml.
 */
public enum Algorithm {
    TOKEN_BUCKET,
    SLIDING_WINDOW_LOG,
    SLIDING_WINDOW_COUNTER
}
