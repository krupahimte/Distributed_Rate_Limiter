package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

class RateLimiterRegistryTest {

    private final RateLimiter tokenBucket = mock(RateLimiter.class);
    private final RateLimiter slidingWindowLog = mock(RateLimiter.class);

    private final RateLimiterRegistry registry = new RateLimiterRegistry(Map.of(
            Algorithm.TOKEN_BUCKET, tokenBucket,
            Algorithm.SLIDING_WINDOW_LOG, slidingWindowLog), Algorithm.TOKEN_BUCKET);

    static class Endpoints {
        @RateLimit(capacity = 1, refillRate = 1)
        void noOverride() {
        }

        @RateLimit(capacity = 1, refillRate = 1, algorithm = Algorithm.SLIDING_WINDOW_LOG)
        void slidingLog() {
        }

        @RateLimit(capacity = 1, refillRate = 1, algorithm = Algorithm.SLIDING_WINDOW_COUNTER)
        void unregistered() {
        }

        @RateLimit(capacity = 1, refillRate = 1,
                algorithm = {Algorithm.TOKEN_BUCKET, Algorithm.SLIDING_WINDOW_LOG})
        void twoAlgorithms() {
        }
    }

    private static RateLimit annotationOf(String methodName) throws NoSuchMethodException {
        return Endpoints.class.getDeclaredMethod(methodName).getAnnotation(RateLimit.class);
    }

    @Test
    void endpointWithoutOverrideGetsDefault() throws Exception {
        assertThat(registry.limiterFor(annotationOf("noOverride"))).isSameAs(tokenBucket);
        assertThat(registry.defaultLimiter()).isSameAs(tokenBucket);
        assertThat(registry.defaultAlgorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
    }

    @Test
    void endpointWithOverrideGetsThatAlgorithm() throws Exception {
        assertThat(registry.limiterFor(annotationOf("slidingLog"))).isSameAs(slidingWindowLog);
    }

    @Test
    void overrideToUnregisteredAlgorithmFails() {
        assertThatIllegalStateException()
                .isThrownBy(() -> registry.limiterFor(annotationOf("unregistered")))
                .withMessageContaining("SLIDING_WINDOW_COUNTER");
    }

    @Test
    void moreThanOneAlgorithmIsRejected() {
        assertThatIllegalStateException()
                .isThrownBy(() -> registry.limiterFor(annotationOf("twoAlgorithms")))
                .withMessageContaining("at most one algorithm");
    }

    @Test
    void defaultAlgorithmMustBeRegistered() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new RateLimiterRegistry(Map.of(Algorithm.TOKEN_BUCKET, tokenBucket),
                        Algorithm.SLIDING_WINDOW_LOG))
                .withMessageContaining("SLIDING_WINDOW_LOG");
    }
}
