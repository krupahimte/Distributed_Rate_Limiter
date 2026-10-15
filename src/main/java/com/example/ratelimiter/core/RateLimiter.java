package com.example.ratelimiter.core;

/**
 * Strategy interface implemented once per {@link Algorithm}.
 * Implementations must be atomic across application instances.
 */
public interface RateLimiter {

    /**
     * Attempts to consume one request for {@code key}.
     *
     * @param key  fully qualified identity of the caller and endpoint
     *             (for example {@code rate_limit:Demo.search:user:42})
     * @param rule the limit to enforce
     * @return whether the request is allowed, plus header values
     */
    RateLimitResult tryAcquire(String key, RateLimitRule rule);

    Algorithm algorithm();
}
