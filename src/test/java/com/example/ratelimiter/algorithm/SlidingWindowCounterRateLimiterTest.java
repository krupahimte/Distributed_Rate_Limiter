package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Java side. The Lua algorithm is tested against real Redis in
 * {@link SlidingWindowCounterScriptTest}.
 */
@ExtendWith(MockitoExtension.class)
class SlidingWindowCounterRateLimiterTest {

    // capacity 10, refilled 5/s -> window 2000 ms
    private static final RateLimitRule RULE = new RateLimitRule(10, 5, Duration.ofSeconds(1));

    @Mock
    private StringRedisTemplate redis;

    private SlidingWindowCounterRateLimiter limiter() {
        return new SlidingWindowCounterRateLimiter(redis);
    }

    private void scriptReturns(List<Long> reply) {
        when(redis.execute(eq(SlidingWindowCounterRateLimiter.SCRIPT), eq(List.of("rl:k:swc")),
                eq("10"), eq("2000")))
                .thenReturn(reply);
    }

    @Test
    void passesLimitAndWindowToScript() {
        scriptReturns(List.of(1L, 9L, 0L));

        limiter().tryAcquire("rl:k", RULE);

        verify(redis).execute(eq(SlidingWindowCounterRateLimiter.SCRIPT), eq(List.of("rl:k:swc")),
                eq("10"), eq("2000"));
    }

    @Test
    void mapsAllowedReply() {
        scriptReturns(List.of(1L, 9L, 0L));

        assertThat(limiter().tryAcquire("rl:k", RULE)).isEqualTo(RateLimitResult.allowed(10, 9));
    }

    @Test
    void mapsDeniedReply() {
        scriptReturns(List.of(0L, 0L, 1500L));

        assertThat(limiter().tryAcquire("rl:k", RULE)).isEqualTo(RateLimitResult.denied(10, 1500));
    }

    @Test
    void reportsItsAlgorithm() {
        assertThat(limiter().algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_COUNTER);
    }
}
