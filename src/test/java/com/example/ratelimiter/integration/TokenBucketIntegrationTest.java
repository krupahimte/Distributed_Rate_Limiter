package com.example.ratelimiter.integration;

import com.example.ratelimiter.core.Algorithm;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "rate-limiter.algorithm=token-bucket")
class TokenBucketIntegrationTest extends AbstractAlgorithmIntegrationTest {

    @Override
    Algorithm expectedAlgorithm() {
        return Algorithm.TOKEN_BUCKET;
    }

    @Override
    String expectedKeySuffix() {
        return ":tb";
    }
}
