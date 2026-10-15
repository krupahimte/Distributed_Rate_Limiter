package com.example.ratelimiter.integration;

import com.example.ratelimiter.core.Algorithm;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "rate-limiter.algorithm=sliding-window-log")
class SlidingWindowLogIntegrationTest extends AbstractAlgorithmIntegrationTest {

    @Override
    Algorithm expectedAlgorithm() {
        return Algorithm.SLIDING_WINDOW_LOG;
    }

    @Override
    String expectedKeySuffix() {
        return ":swl";
    }
}
