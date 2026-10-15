package com.example.ratelimiter.integration;

import com.example.ratelimiter.core.Algorithm;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "rate-limiter.algorithm=sliding-window-counter")
class SlidingWindowCounterIntegrationTest extends AbstractAlgorithmIntegrationTest {

    @Override
    Algorithm expectedAlgorithm() {
        return Algorithm.SLIDING_WINDOW_COUNTER;
    }

    @Override
    String expectedKeySuffix() {
        return ":swc";
    }
}
