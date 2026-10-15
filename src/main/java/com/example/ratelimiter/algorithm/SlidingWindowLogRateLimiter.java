package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Sliding window log: records the timestamp of every allowed request and allows a new one
 * only if fewer than {@code capacity} happened in the last {@link RateLimitRule#windowMillis()}.
 * Exact, but memory grows with the limit (one sorted-set entry per request in the window).
 *
 * <p>The algorithm lives in {@code scripts/sliding_window_log.lua} and runs atomically in Redis.
 */
@Component
public class SlidingWindowLogRateLimiter implements RateLimiter {

    static final String KEY_SUFFIX = ":swl";

    @SuppressWarnings("rawtypes")
    static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/sliding_window_log.lua"), List.class);

    private final StringRedisTemplate redis;

    public SlidingWindowLogRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        List<?> reply = redis.execute(
                SCRIPT,
                List.of(key + KEY_SUFFIX),
                Long.toString(rule.capacity()),
                Long.toString(rule.windowMillis()),
                UUID.randomUUID().toString());
        return LuaReplies.toResult(reply, rule.capacity());
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.SLIDING_WINDOW_LOG;
    }
}
