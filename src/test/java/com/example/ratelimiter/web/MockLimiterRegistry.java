package com.example.ratelimiter.web;

import com.example.ratelimiter.algorithm.RateLimiterRegistry;
import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimiter;
import org.springframework.context.annotation.Bean;

import java.util.EnumMap;
import java.util.Map;

/**
 * For web slice tests: a registry that returns the context's (mocked) {@link RateLimiter} for every
 * algorithm. Use with {@code @Import}. Deliberately not a {@code @Configuration}, so component
 * scanning in other tests' application instances never picks it up.
 */
public class MockLimiterRegistry {

    @Bean
    RateLimiterRegistry rateLimiterRegistry(RateLimiter rateLimiter) {
        Map<Algorithm, RateLimiter> limiters = new EnumMap<>(Algorithm.class);
        for (Algorithm algorithm : Algorithm.values()) {
            limiters.put(algorithm, rateLimiter);
        }
        return new RateLimiterRegistry(limiters, Algorithm.TOKEN_BUCKET);
    }
}
