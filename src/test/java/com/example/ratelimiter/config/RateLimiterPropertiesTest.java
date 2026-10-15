package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Algorithm;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RateLimiterConfig.class);

    @Test
    void usesDefaultsWhenNothingConfigured() {
        runner.run(context -> {
            RateLimiterProperties props = context.getBean(RateLimiterProperties.class);
            assertThat(props.enabled()).isTrue();
            assertThat(props.algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
            assertThat(props.failOpen()).isTrue();
            assertThat(props.keyPrefix()).isEqualTo("rate_limit");
        });
    }

    @Test
    void bindsCustomValuesWithKebabCaseAlgorithm() {
        runner.withPropertyValues(
                        "rate-limiter.enabled=false",
                        "rate-limiter.algorithm=sliding-window-counter",
                        "rate-limiter.fail-open=false",
                        "rate-limiter.key-prefix=rl")
                .run(context -> {
                    RateLimiterProperties props = context.getBean(RateLimiterProperties.class);
                    assertThat(props.enabled()).isFalse();
                    assertThat(props.algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW_COUNTER);
                    assertThat(props.failOpen()).isFalse();
                    assertThat(props.keyPrefix()).isEqualTo("rl");
                });
    }

    @Test
    void failsStartupOnUnknownAlgorithm() {
        runner.withPropertyValues("rate-limiter.algorithm=leaky-bucket")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining("leaky-bucket");
                });
    }

    @Test
    void failsStartupOnBlankKeyPrefix() {
        runner.withPropertyValues("rate-limiter.key-prefix= ")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause().isInstanceOf(BindValidationException.class)
                            .hasMessageContaining("keyPrefix");
                });
    }
}
