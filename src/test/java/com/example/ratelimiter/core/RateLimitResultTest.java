package com.example.ratelimiter.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitResultTest {

    @Test
    void allowedResultHasNoRetryAfter() {
        RateLimitResult result = RateLimitResult.allowed(10, 7);

        assertThat(result.allowed()).isTrue();
        assertThat(result.limit()).isEqualTo(10);
        assertThat(result.remaining()).isEqualTo(7);
        assertThat(result.retryAfterSeconds()).isZero();
    }

    @Test
    void deniedResultHasZeroRemaining() {
        RateLimitResult result = RateLimitResult.denied(10, 2500);

        assertThat(result.allowed()).isFalse();
        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterMillis()).isEqualTo(2500);
    }

    @Test
    void clampsNegativeValuesToZero() {
        RateLimitResult result = new RateLimitResult(true, 10, -3, -1);

        assertThat(result.remaining()).isZero();
        assertThat(result.retryAfterMillis()).isZero();
    }

    @ParameterizedTest(name = "{0} ms -> Retry-After {1}s")
    @CsvSource({"0, 1", "1, 1", "999, 1", "1000, 1", "1001, 2", "2500, 3"})
    void retryAfterRoundsUpToWholeSecondsWithMinimumOfOne(long millis, long expectedSeconds) {
        assertThat(RateLimitResult.denied(10, millis).retryAfterSeconds()).isEqualTo(expectedSeconds);
    }
}
