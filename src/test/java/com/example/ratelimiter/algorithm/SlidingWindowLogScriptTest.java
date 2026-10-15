package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the sliding window log Lua script against real Redis.
 */
class SlidingWindowLogScriptTest extends RedisContainerSupport {

    private final SlidingWindowLogRateLimiter limiter = new SlidingWindowLogRateLimiter(redis);

    /** {@code limit} requests per {@code windowMs} (refill of limit tokens per window). */
    private static RateLimitRule rule(long limit, long windowMs) {
        return new RateLimitRule(limit, limit, Duration.ofMillis(windowMs));
    }

    @Test
    void allowsExactlyLimitWithinWindowAndCountsDown() {
        RateLimitRule rule = rule(3, 60_000);

        List<Long> remaining = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RateLimitResult result = limiter.tryAcquire("k", rule);
            assertThat(result.allowed()).isTrue();
            remaining.add(result.remaining());
        }

        assertThat(remaining).containsExactly(2L, 1L, 0L);
        assertThat(limiter.tryAcquire("k", rule).allowed()).isFalse();
    }

    @Test
    void retryAfterIsTimeUntilOldestRequestLeavesWindow() throws InterruptedException {
        RateLimitRule rule = rule(1, 1000);
        limiter.tryAcquire("k", rule);
        Thread.sleep(300);

        RateLimitResult denied = limiter.tryAcquire("k", rule);

        assertThat(denied.allowed()).isFalse();
        // Oldest entry is ~300 ms old, so it leaves in ~700 ms
        assertThat(denied.retryAfterMillis()).isBetween(1L, 750L);
    }

    @Test
    void windowSlidesOneRequestAtATime() throws InterruptedException {
        RateLimitRule rule = rule(2, 400);
        assertThat(limiter.tryAcquire("k", rule).allowed()).isTrue();   // A at ~0 ms
        Thread.sleep(200);
        assertThat(limiter.tryAcquire("k", rule).allowed()).isTrue();   // B at ~200 ms
        assertThat(limiter.tryAcquire("k", rule).allowed()).isFalse();

        Thread.sleep(250);                                              // ~450 ms: A has left, B has not

        assertThat(limiter.tryAcquire("k", rule).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", rule).allowed()).isFalse();
    }

    @Test
    void deniedRequestsAreNotLogged() {
        RateLimitRule rule = rule(2, 60_000);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("k", rule);
        }

        assertThat(redis.opsForZSet().zCard("k" + SlidingWindowLogRateLimiter.KEY_SUFFIX)).isEqualTo(2);
    }

    @Test
    void keysAreIndependent() {
        RateLimitRule rule = rule(1, 60_000);

        assertThat(limiter.tryAcquire("alice", rule).allowed()).isTrue();
        assertThat(limiter.tryAcquire("alice", rule).allowed()).isFalse();
        assertThat(limiter.tryAcquire("bob", rule).allowed()).isTrue();
    }

    @Test
    void setsExpiryToWindowLength() {
        limiter.tryAcquire("k", rule(5, 10_000));

        Long ttlMs = redis.getExpire("k" + SlidingWindowLogRateLimiter.KEY_SUFFIX, TimeUnit.MILLISECONDS);

        assertThat(ttlMs).isBetween(1L, 10_000L);
    }

    @Test
    void handlesLimitLoweredWhileEntriesExist() {
        for (int i = 0; i < 5; i++) {
            limiter.tryAcquire("k", rule(5, 60_000));
        }

        RateLimitResult result = limiter.tryAcquire("k", rule(2, 60_000));

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterMillis()).isBetween(1L, 60_000L);
    }
}
