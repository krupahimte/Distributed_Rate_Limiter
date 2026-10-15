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
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Java side: what is sent to Redis and how the reply is interpreted.
 * The Lua algorithm itself is tested against real Redis in {@link TokenBucketScriptTest}.
 */
@ExtendWith(MockitoExtension.class)
class TokenBucketRateLimiterTest {

    private static final RateLimitRule RULE = new RateLimitRule(10, 5, Duration.ofSeconds(1));

    @Mock
    private StringRedisTemplate redis;

    private TokenBucketRateLimiter limiter() {
        return new TokenBucketRateLimiter(redis);
    }

    private void scriptReturns(List<Long> reply) {
        when(redis.execute(eq(TokenBucketRateLimiter.SCRIPT), eq(List.of("rl:k:tb")),
                eq("10"), eq("0.005"), eq("1")))
                .thenReturn(reply);
    }

    @Test
    void passesKeyCapacityAndRefillRateToScript() {
        scriptReturns(List.of(1L, 9L, 0L));

        limiter().tryAcquire("rl:k", RULE);

        verify(redis).execute(eq(TokenBucketRateLimiter.SCRIPT), eq(List.of("rl:k:tb")),
                eq("10"), eq("0.005"), eq("1"));
    }

    @Test
    void mapsAllowedReply() {
        scriptReturns(List.of(1L, 9L, 0L));

        RateLimitResult result = limiter().tryAcquire("rl:k", RULE);

        assertThat(result).isEqualTo(RateLimitResult.allowed(10, 9));
    }

    @Test
    void mapsDeniedReply() {
        scriptReturns(List.of(0L, 0L, 200L));

        RateLimitResult result = limiter().tryAcquire("rl:k", RULE);

        assertThat(result).isEqualTo(RateLimitResult.denied(10, 200));
    }

    @Test
    void rejectsMalformedReply() {
        scriptReturns(List.of(1L));

        assertThatIllegalStateException().isThrownBy(() -> limiter().tryAcquire("rl:k", RULE));
    }

    @Test
    void reportsItsAlgorithm() {
        assertThat(limiter().algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
    }
}
