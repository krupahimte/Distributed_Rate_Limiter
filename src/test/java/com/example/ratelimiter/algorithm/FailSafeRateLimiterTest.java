package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class FailSafeRateLimiterTest {

    private static final RateLimitRule RULE = new RateLimitRule(10, 10, Duration.ofSeconds(1));
    private static final String WARNING = "Redis unavailable, failing";

    @Mock
    private RateLimiter delegate;

    private final MutableClock clock = new MutableClock();

    static List<DataAccessException> redisFailures() {
        return List.of(
                new RedisConnectionFailureException("Connection refused"),
                new QueryTimeoutException("Command timed out"),
                new RedisSystemException("Script error", new RuntimeException()));
    }

    @Test
    void returnsDelegateResultWhenRedisIsHealthy() {
        RateLimitResult denied = RateLimitResult.denied(10, 300);
        when(delegate.tryAcquire("k", RULE)).thenReturn(denied);

        assertThat(new FailSafeRateLimiter(delegate, true, clock).tryAcquire("k", RULE)).isEqualTo(denied);
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void failOpenAllowsRequestWhenRedisFails(DataAccessException failure) {
        when(delegate.tryAcquire("k", RULE)).thenThrow(failure);

        RateLimitResult result = new FailSafeRateLimiter(delegate, true, clock).tryAcquire("k", RULE);

        assertThat(result).isEqualTo(RateLimitResult.allowed(10, 10));
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    void failClosedRejectsRequestWhenRedisFails(DataAccessException failure) {
        when(delegate.tryAcquire("k", RULE)).thenThrow(failure);

        RateLimitResult result = new FailSafeRateLimiter(delegate, false, clock).tryAcquire("k", RULE);

        assertThat(result).isEqualTo(RateLimitResult.denied(10, FailSafeRateLimiter.FAIL_CLOSED_RETRY_AFTER_MS));
        assertThat(result.retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void doesNotSwallowExceptionsThatAreNotRedisFailures() {
        when(delegate.tryAcquire("k", RULE)).thenThrow(new IllegalStateException("bug"));
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(delegate, true, clock);

        assertThatIllegalStateException().isThrownBy(() -> limiter.tryAcquire("k", RULE));
    }

    @Test
    void logsWarningAtMostOncePerIntervalAndOnceOnRecovery(CapturedOutput output) {
        when(delegate.tryAcquire(anyString(), any())).thenThrow(new RedisConnectionFailureException("down"));
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(delegate, true, clock);

        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k", RULE);
        }
        assertThat(countOccurrences(output.getOut(), WARNING)).isEqualTo(1);
        assertThat(output.getOut()).contains("failing OPEN (requests allowed) (0 similar failures suppressed)");

        clock.advance(FailSafeRateLimiter.LOG_INTERVAL);
        limiter.tryAcquire("k", RULE);
        assertThat(countOccurrences(output.getOut(), WARNING)).isEqualTo(2);
        assertThat(output.getOut()).contains("(2 similar failures suppressed)");

        doReturn(RateLimitResult.allowed(10, 9)).when(delegate).tryAcquire(anyString(), any());
        limiter.tryAcquire("k", RULE);
        limiter.tryAcquire("k", RULE);
        assertThat(countOccurrences(output.getOut(), "Redis is reachable again")).isEqualTo(1);
        assertThat(output.getOut()).contains("(4 failures while unavailable)");
    }

    @Test
    void logsFailClosedModeInWarning(CapturedOutput output) {
        when(delegate.tryAcquire("k", RULE)).thenThrow(new RedisConnectionFailureException("down"));

        new FailSafeRateLimiter(delegate, false, clock).tryAcquire("k", RULE);

        assertThat(output.getOut()).contains("failing CLOSED (requests rejected)");
    }

    @Test
    void exposesDelegateAlgorithmAndMode() {
        when(delegate.algorithm()).thenReturn(Algorithm.SLIDING_WINDOW_LOG);
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(delegate, false, clock);

        assertThat(limiter.algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_LOG);
        assertThat(limiter.delegate()).isSameAs(delegate);
        assertThat(limiter.failOpen()).isFalse();
    }

    private static int countOccurrences(String text, String fragment) {
        return text.split(java.util.regex.Pattern.quote(fragment), -1).length - 1;
    }

    /** Clock the test can move forward, so the log interval can be tested without sleeping. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
