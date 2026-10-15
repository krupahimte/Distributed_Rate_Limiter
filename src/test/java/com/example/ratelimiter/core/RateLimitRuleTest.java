package com.example.ratelimiter.core;

import com.example.ratelimiter.annotation.KeyType;
import com.example.ratelimiter.annotation.RateLimit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

class RateLimitRuleTest {

    @RateLimit(capacity = 20, refillRate = 10, refillPeriod = 1, unit = ChronoUnit.MINUTES, key = KeyType.USER)
    void annotated() {
    }

    @Test
    void buildsFromAnnotation() throws NoSuchMethodException {
        RateLimit annotation = getClass().getDeclaredMethod("annotated").getAnnotation(RateLimit.class);

        RateLimitRule rule = RateLimitRule.from(annotation);

        assertThat(rule.capacity()).isEqualTo(20);
        assertThat(rule.refillRate()).isEqualTo(10);
        assertThat(rule.refillPeriod()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void computesRefillRatePerMillisecond() {
        RateLimitRule rule = new RateLimitRule(10, 5, Duration.ofSeconds(1));

        assertThat(rule.tokensPerMillisecond()).isCloseTo(0.005, within(1e-12));
    }

    @ParameterizedTest(name = "capacity={0}, refill={1}/{2}ms -> window={3}ms")
    @CsvSource({
            "10, 10, 60000, 60000",   // 10/min, no extra burst -> 1 minute window
            "20, 10, 60000, 120000",  // burst of 20 at 10/min -> 20 per 2 minutes
            "5,  1,  1000,  5000",
            "1,  1000, 1,   1"        // would be 0.001 ms; clamped to 1 ms
    })
    void windowKeepsSameSustainedRateAsTokenBucket(long capacity, long refillRate, long periodMs, long expectedWindow) {
        RateLimitRule rule = new RateLimitRule(capacity, refillRate, Duration.ofMillis(periodMs));

        assertThat(rule.windowMillis()).isEqualTo(expectedWindow);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitRule(0, 1, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitRule(1, 0, Duration.ofSeconds(1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitRule(1, 1, Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitRule(1, 1, Duration.ofSeconds(-1)));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitRule(1, 1, null));
    }
}
