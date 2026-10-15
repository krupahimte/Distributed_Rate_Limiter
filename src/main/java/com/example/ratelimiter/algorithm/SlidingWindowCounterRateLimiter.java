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

/**
 * Sliding window counter: keeps request counts for the current and previous fixed windows and
 * weights the previous count by how much of it overlaps the sliding window. Constant memory per
 * key, at the cost of being an approximation that assumes requests were evenly spread.
 *
 * <p>The algorithm lives in {@code scripts/sliding_window_counter.lua} and runs atomically in Redis.
 */
@Component
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    static final String KEY_SUFFIX = ":swc";

    @SuppressWarnings("rawtypes")
    static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/sliding_window_counter.lua"), List.class);

    private final StringRedisTemplate redis;

    public SlidingWindowCounterRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        List<?> reply = redis.execute(
                SCRIPT,
                List.of(key + KEY_SUFFIX),
                Long.toString(rule.capacity()),
                Long.toString(rule.windowMillis()));
        return LuaReplies.toResult(reply, rule.capacity());
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.SLIDING_WINDOW_COUNTER;
    }
}
