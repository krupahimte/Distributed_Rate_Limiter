package com.example.ratelimiter.config;

import com.example.ratelimiter.algorithm.SlidingWindowCounterRateLimiter;
import com.example.ratelimiter.algorithm.SlidingWindowLogRateLimiter;
import com.example.ratelimiter.algorithm.TokenBucketRateLimiter;
import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wires the real beans and Redis client against a port where nothing is listening,
 * so every algorithm hits a genuine connection failure. Needs no Docker.
 */
class RedisUnavailableTest {

    private static final RateLimitRule RULE = new RateLimitRule(5, 5, Duration.ofSeconds(1));

    private static int closedPort;

    @BeforeAll
    static void findClosedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
    }

    private ApplicationContextRunner runner(boolean failOpen, Algorithm algorithm) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(RateLimiterConfig.class, RateLimiterSelectionConfig.class,
                        TokenBucketRateLimiter.class, SlidingWindowLogRateLimiter.class,
                        SlidingWindowCounterRateLimiter.class)
                .withPropertyValues(
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=" + closedPort,
                        "spring.data.redis.connect-timeout=500ms",
                        "spring.data.redis.timeout=500ms",
                        "rate-limiter.algorithm=" + algorithm,
                        "rate-limiter.fail-open=" + failOpen);
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void failOpenAllowsRequestsQuicklyWhenRedisIsDown(Algorithm algorithm) {
        runner(true, algorithm).run(context -> {
            RateLimiter limiter = context.getBean(RateLimiter.class);

            long start = System.nanoTime();
            RateLimitResult result = limiter.tryAcquire("rl:test:ip:1.2.3.4", RULE);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(result).isEqualTo(RateLimitResult.allowed(5, 5));
            assertThat(took).isLessThan(Duration.ofSeconds(3));
        });
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void failClosedRejectsRequestsWhenRedisIsDown(Algorithm algorithm) {
        runner(false, algorithm).run(context -> {
            RateLimitResult result = context.getBean(RateLimiter.class).tryAcquire("rl:test:ip:1.2.3.4", RULE);

            assertThat(result.allowed()).isFalse();
            assertThat(result.retryAfterSeconds()).isEqualTo(1);
        });
    }
}
