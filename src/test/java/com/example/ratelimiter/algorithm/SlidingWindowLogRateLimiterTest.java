package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Java side. The Lua algorithm is tested against real Redis in
 * {@link SlidingWindowLogScriptTest}.
 */
@ExtendWith(MockitoExtension.class)
class SlidingWindowLogRateLimiterTest {

    // 10 per 2 s burst, refilled 5/s -> window 2000 ms
    private static final RateLimitRule RULE = new RateLimitRule(10, 5, Duration.ofSeconds(1));

    @Mock
    private StringRedisTemplate redis;

    private SlidingWindowLogRateLimiter limiter() {
        return new SlidingWindowLogRateLimiter(redis);
    }

    private void scriptReturns(List<Long> reply) {
        when(redis.execute(eq(SlidingWindowLogRateLimiter.SCRIPT), eq(List.of("rl:k:swl")),
                eq("10"), eq("2000"), anyString()))
                .thenReturn(reply);
    }

    @Test
    void passesLimitWindowAndUniqueMemberToScript() {
        scriptReturns(List.of(1L, 9L, 0L));

        limiter().tryAcquire("rl:k", RULE);
        limiter().tryAcquire("rl:k", RULE);

        ArgumentCaptor<String> member = ArgumentCaptor.forClass(String.class);
        verify(redis, times(2)).execute(eq(SlidingWindowLogRateLimiter.SCRIPT), eq(List.of("rl:k:swl")),
                eq("10"), eq("2000"), member.capture());
        assertThat(member.getAllValues()).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(UUID.fromString(id)).isNotNull());
    }

    @Test
    void mapsAllowedReply() {
        scriptReturns(List.of(1L, 9L, 0L));

        assertThat(limiter().tryAcquire("rl:k", RULE)).isEqualTo(RateLimitResult.allowed(10, 9));
    }

    @Test
    void mapsDeniedReply() {
        scriptReturns(List.of(0L, 0L, 750L));

        assertThat(limiter().tryAcquire("rl:k", RULE)).isEqualTo(RateLimitResult.denied(10, 750));
    }

    @Test
    void reportsItsAlgorithm() {
        assertThat(limiter().algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_LOG);
    }
}
