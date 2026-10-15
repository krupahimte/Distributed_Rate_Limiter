package com.example.ratelimiter.config;

import com.example.ratelimiter.algorithm.FailSafeRateLimiter;
import com.example.ratelimiter.algorithm.SlidingWindowCounterRateLimiter;
import com.example.ratelimiter.algorithm.SlidingWindowLogRateLimiter;
import com.example.ratelimiter.algorithm.TokenBucketRateLimiter;
import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimiter;
import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimiterSelectionConfigTest {

    /** Real Redis auto-configuration; nothing connects to Redis unless a limiter is called. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RateLimiterConfig.class, RateLimiterSelectionConfig.class,
                    TokenBucketRateLimiter.class, SlidingWindowLogRateLimiter.class,
                    SlidingWindowCounterRateLimiter.class);

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "token-bucket,           TOKEN_BUCKET",
            "sliding-window-log,     SLIDING_WINDOW_LOG",
            "sliding-window-counter, SLIDING_WINDOW_COUNTER",
            "SLIDING_WINDOW_LOG,     SLIDING_WINDOW_LOG"
    })
    void injectsConfiguredAlgorithmWrappedInFailSafeDecorator(String configured, Algorithm expected) {
        runner.withPropertyValues("rate-limiter.algorithm=" + configured).run(context -> {
            RateLimiter limiter = context.getBean(RateLimiter.class);

            assertThat(limiter).isInstanceOf(FailSafeRateLimiter.class);
            FailSafeRateLimiter failSafe = (FailSafeRateLimiter) limiter;
            assertThat(failSafe.delegate().algorithm()).isEqualTo(expected);
            assertThat(failSafe.delegate()).isSameAs(context.getBean(implementationOf(expected)));
        });
    }

    @Test
    void defaultsToTokenBucketAndFailOpen() {
        runner.run(context -> {
            FailSafeRateLimiter limiter = context.getBean(FailSafeRateLimiter.class);

            assertThat(limiter.algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
            assertThat(limiter.failOpen()).isTrue();
        });
    }

    @Test
    void appliesFailClosedSetting() {
        runner.withPropertyValues("rate-limiter.fail-open=false")
                .run(context -> assertThat(context.getBean(FailSafeRateLimiter.class).failOpen()).isFalse());
    }

    @Test
    void redisClientRejectsCommandsWhileDisconnectedInsteadOfQueueing() {
        runner.run(context -> {
            ClientOptions options = context.getBean(LettuceConnectionFactory.class)
                    .getClientConfiguration().getClientOptions().orElseThrow();

            assertThat(options.getDisconnectedBehavior())
                    .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        });
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void selectFindsTheMatchingImplementation(Algorithm algorithm) {
        List<RateLimiter> candidates = List.of(
                limiterFor(Algorithm.TOKEN_BUCKET),
                limiterFor(Algorithm.SLIDING_WINDOW_LOG),
                limiterFor(Algorithm.SLIDING_WINDOW_COUNTER));

        assertThat(RateLimiterSelectionConfig.select(algorithm, candidates).algorithm()).isEqualTo(algorithm);
    }

    @Test
    void selectFailsWhenNoImplementationMatches() {
        List<RateLimiter> candidates = List.of(limiterFor(Algorithm.TOKEN_BUCKET));

        assertThatIllegalStateException()
                .isThrownBy(() -> RateLimiterSelectionConfig.select(Algorithm.SLIDING_WINDOW_LOG, candidates))
                .withMessageContaining("SLIDING_WINDOW_LOG").withMessageContaining("found 0");
    }

    @Test
    void selectFailsWhenImplementationIsAmbiguous() {
        List<RateLimiter> candidates = List.of(
                limiterFor(Algorithm.TOKEN_BUCKET), limiterFor(Algorithm.TOKEN_BUCKET));

        assertThatIllegalStateException()
                .isThrownBy(() -> RateLimiterSelectionConfig.select(Algorithm.TOKEN_BUCKET, candidates))
                .withMessageContaining("found 2");
    }

    private static RateLimiter limiterFor(Algorithm algorithm) {
        RateLimiter limiter = mock(RateLimiter.class);
        when(limiter.algorithm()).thenReturn(algorithm);
        return limiter;
    }

    private static Class<? extends RateLimiter> implementationOf(Algorithm algorithm) {
        return switch (algorithm) {
            case TOKEN_BUCKET -> TokenBucketRateLimiter.class;
            case SLIDING_WINDOW_LOG -> SlidingWindowLogRateLimiter.class;
            case SLIDING_WINDOW_COUNTER -> SlidingWindowCounterRateLimiter.class;
        };
    }
}
