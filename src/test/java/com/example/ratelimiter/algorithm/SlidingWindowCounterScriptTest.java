package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisCallback;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the sliding window counter Lua script against real Redis.
 *
 * <p>Fixed windows are aligned to the clock, so instead of sleeping across window boundaries
 * these tests use a one-hour window and seed the previous window's count directly. The weight
 * of the previous window is then computed from Redis' own clock.
 */
class SlidingWindowCounterScriptTest extends RedisContainerSupport {

    private static final long HOUR_MS = 3_600_000;
    private static final String KEY = "k" + SlidingWindowCounterRateLimiter.KEY_SUFFIX;

    private final SlidingWindowCounterRateLimiter limiter = new SlidingWindowCounterRateLimiter(redis);

    private static RateLimitRule perHour(long limit) {
        return new RateLimitRule(limit, limit, Duration.ofMillis(HOUR_MS));
    }

    private static long redisNowMs() {
        return redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
    }

    /** Pretends {@code count} requests were allowed {@code windowsAgo} fixed windows ago. */
    private static void seedPastWindow(long count, long windowsAgo) {
        long currentWindow = redisNowMs() / HOUR_MS;
        redis.opsForHash().putAll(KEY, Map.of(
                "window", Long.toString(currentWindow - windowsAgo),
                "curr", Long.toString(count),
                "prev", "0"));
    }

    @Test
    void allowsExactlyLimitInFreshWindowAndCountsDown() {
        RateLimitRule rule = perHour(3);

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
    void deniedRequestsDoNotIncrementCounter() {
        RateLimitRule rule = perHour(2);
        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("k", rule);
        }

        assertThat(redis.opsForHash().get(KEY, "curr")).isEqualTo("2");
    }

    @Test
    void previousWindowCountsInProportionToItsOverlap() {
        seedPastWindow(100, 1);
        // Fraction of the current fixed window already elapsed = fraction of prev no longer counted
        double elapsedFraction = (redisNowMs() % HOUR_MS) / (double) HOUR_MS;
        long expectedAllowed = (long) Math.floor(100 * elapsedFraction);

        int allowed = 0;
        RateLimitResult last;
        do {
            last = limiter.tryAcquire("k", perHour(100));
            if (last.allowed()) {
                allowed++;
            }
        } while (last.allowed() && allowed <= 100);

        // ±1 for the few milliseconds that pass while the test runs
        assertThat(allowed).isBetween((int) Math.max(0, expectedAllowed - 1), (int) expectedAllowed + 1);
        assertThat(last.retryAfterMillis()).isBetween(1L, HOUR_MS);
    }

    @Test
    void windowsOlderThanThePreviousOneAreIgnored() {
        seedPastWindow(100, 2);

        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (limiter.tryAcquire("k", perHour(10)).allowed()) {
                allowed++;
            }
        }

        assertThat(allowed).isEqualTo(10);
    }

    @Test
    void retryAfterWhenCurrentWindowIsFullReachesIntoNextWindow() {
        RateLimitRule rule = perHour(2);
        long msLeftInWindow = HOUR_MS - redisNowMs() % HOUR_MS;
        limiter.tryAcquire("k", rule);
        limiter.tryAcquire("k", rule);

        RateLimitResult denied = limiter.tryAcquire("k", rule);

        // Wait out this window, then half of the next until 2 * (1 - x) + 1 <= 2, i.e. x >= 0.5
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis())
                .isBetween(msLeftInWindow + HOUR_MS / 2 - 1000, msLeftInWindow + HOUR_MS / 2);
    }

    @Test
    void keysAreIndependent() {
        RateLimitRule rule = perHour(1);

        assertThat(limiter.tryAcquire("alice", rule).allowed()).isTrue();
        assertThat(limiter.tryAcquire("alice", rule).allowed()).isFalse();
        assertThat(limiter.tryAcquire("bob", rule).allowed()).isTrue();
    }

    @Test
    void keepsKeyUntilEndOfNextWindow() {
        long msLeftInWindow = HOUR_MS - redisNowMs() % HOUR_MS;
        limiter.tryAcquire("k", perHour(5));

        Long ttlMs = redis.getExpire(KEY, TimeUnit.MILLISECONDS);

        assertThat(ttlMs).isBetween(msLeftInWindow + HOUR_MS - 1000, msLeftInWindow + HOUR_MS);
    }
}
