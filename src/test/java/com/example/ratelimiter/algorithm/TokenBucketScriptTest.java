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
 * Tests the token bucket Lua script against real Redis.
 */
class TokenBucketScriptTest extends RedisContainerSupport {

    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(redis);

    @Test
    void newBucketStartsFullAndCountsDown() {
        RateLimitRule rule = new RateLimitRule(3, 1, Duration.ofMinutes(1));

        List<Long> remaining = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RateLimitResult result = limiter.tryAcquire("k", rule);
            assertThat(result.allowed()).isTrue();
            remaining.add(result.remaining());
        }

        assertThat(remaining).containsExactly(2L, 1L, 0L);
    }

    @Test
    void deniesWhenEmptyAndReportsTimeUntilNextToken() {
        // 1 token per second -> next token in at most 1000 ms
        RateLimitRule rule = new RateLimitRule(2, 1, Duration.ofSeconds(1));
        limiter.tryAcquire("k", rule);
        limiter.tryAcquire("k", rule);

        RateLimitResult denied = limiter.tryAcquire("k", rule);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.remaining()).isZero();
        assertThat(denied.retryAfterMillis()).isBetween(1L, 1000L);
    }

    @Test
    void deniedRequestsDoNotConsumeTokens() throws InterruptedException {
        // 1 token per 200 ms
        RateLimitRule rule = new RateLimitRule(1, 1, Duration.ofMillis(200));
        assertThat(limiter.tryAcquire("k", rule).allowed()).isTrue();
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k", rule).allowed()).isFalse();
        }

        Thread.sleep(300);

        assertThat(limiter.tryAcquire("k", rule).allowed()).isTrue();
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        // 10 tokens per second = 1 token per 100 ms
        RateLimitRule rule = new RateLimitRule(2, 10, Duration.ofSeconds(1));
        limiter.tryAcquire("k", rule);
        limiter.tryAcquire("k", rule);
        assertThat(limiter.tryAcquire("k", rule).allowed()).isFalse();

        Thread.sleep(250);

        assertThat(limiter.tryAcquire("k", rule).allowed()).isTrue();
    }

    @Test
    void neverRefillsBeyondCapacity() throws InterruptedException {
        // 1 token per 100 ms; after 600 ms idle there would be 6 tokens without the cap
        RateLimitRule rule = new RateLimitRule(3, 1, Duration.ofMillis(100));
        limiter.tryAcquire("k", rule);

        Thread.sleep(600);

        int allowed = 0;
        for (int i = 0; i < 6; i++) {
            if (limiter.tryAcquire("k", rule).allowed()) {
                allowed++;
            }
        }
        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void keysAreIndependent() {
        RateLimitRule rule = new RateLimitRule(1, 1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("alice", rule).allowed()).isTrue();
        assertThat(limiter.tryAcquire("alice", rule).allowed()).isFalse();
        assertThat(limiter.tryAcquire("bob", rule).allowed()).isTrue();
    }

    @Test
    void setsExpiryNoLongerThanTimeToRefillCompletely() {
        // Full refill of 10 tokens at 1 token/second takes 10 s
        RateLimitRule rule = new RateLimitRule(10, 1, Duration.ofSeconds(1));
        limiter.tryAcquire("k", rule);

        Long ttlMs = redis.getExpire("k" + TokenBucketRateLimiter.KEY_SUFFIX, TimeUnit.MILLISECONDS);

        assertThat(ttlMs).isBetween(1L, 10_000L);
    }
}
