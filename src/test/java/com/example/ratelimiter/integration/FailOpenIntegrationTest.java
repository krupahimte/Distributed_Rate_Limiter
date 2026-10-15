package com.example.ratelimiter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real Redis outage (container paused) with {@code fail-open=true}: requests keep flowing,
 * and limiting resumes once Redis is back.
 */
@TestPropertySource(properties = "rate-limiter.fail-open=true")
@ExtendWith(OutputCaptureExtension.class)
class FailOpenIntegrationTest extends RedisIntegrationTestSupport {

    @Test
    void allowsRequestsWhileRedisIsDownAndResumesLimitingAfterwards(CapturedOutput output) {
        // Use up alice's report limit (2) while Redis is healthy
        assertThat(post("/api/reports", "alice").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post("/api/reports", "alice").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        pauseRedis();
        try {
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                ResponseEntity<String> response = post("/api/reports", "alice");
                Duration took = Duration.ofNanos(System.nanoTime() - start);

                // Normally 429, but Redis cannot be asked, so fail open
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                // Bounded by spring.data.redis.timeout (500ms), not hanging
                assertThat(took).isLessThan(Duration.ofSeconds(3));
            }
        } finally {
            unpauseRedis();
        }
        assertThat(output.getOut()).contains("Redis unavailable, failing OPEN (requests allowed)");

        // Redis is back and still remembers alice used her limit
        assertThat(post("/api/reports", "alice").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(output.getOut()).contains("Redis is reachable again");
    }
}
