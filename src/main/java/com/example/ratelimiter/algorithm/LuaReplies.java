package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.RateLimitResult;

import java.util.List;

/**
 * Converts the {@code {allowed, remaining, retry_after_ms}} array every algorithm script
 * returns into a {@link RateLimitResult}.
 */
final class LuaReplies {

    private LuaReplies() {
    }

    static RateLimitResult toResult(List<?> reply, long limit) {
        if (reply == null || reply.size() != 3) {
            throw new IllegalStateException("Unexpected rate limit script reply: " + reply);
        }
        boolean allowed = asLong(reply.get(0)) == 1;
        return allowed
                ? RateLimitResult.allowed(limit, asLong(reply.get(1)))
                : RateLimitResult.denied(limit, asLong(reply.get(2)));
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Expected integer in rate limit script reply, got: " + value);
    }
}
