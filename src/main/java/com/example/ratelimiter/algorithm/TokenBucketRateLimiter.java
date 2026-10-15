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
 * Token bucket: the bucket holds up to {@code capacity} tokens, refilled continuously at
 * {@code refillRate / refillPeriod}; each request takes one token. Allows bursts up to
 * capacity, then smooths to the refill rate.
 *
 * <p>The algorithm itself lives in {@code scripts/token_bucket.lua} and runs atomically in Redis.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    /** Keeps this algorithm's data apart from the others if the configured algorithm changes. */
    static final String KEY_SUFFIX = ":tb";

    @SuppressWarnings("rawtypes")
    static final RedisScript<List> SCRIPT =
            RedisScript.of(new ClassPathResource("scripts/token_bucket.lua"), List.class);

    private final StringRedisTemplate redis;

    public TokenBucketRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        List<?> reply = redis.execute(
                SCRIPT,
                List.of(key + KEY_SUFFIX),
                Long.toString(rule.capacity()),
                Double.toString(rule.tokensPerMillisecond()),
                "1");
        return LuaReplies.toResult(reply, rule.capacity());
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.TOKEN_BUCKET;
    }
}
