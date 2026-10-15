package com.example.ratelimiter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real Redis outage (container paused) with {@code fail-open=false}: requests are rejected
 * with 429 until Redis is back.
 */
@TestPropertySource(properties = "rate-limiter.fail-open=false")
@ExtendWith(OutputCaptureExtension.class)
class FailClosedIntegrationTest extends RedisIntegrationTestSupport {

    @Test
    void rejectsRequestsWhileRedisIsDownAndRecoversAfterwards(CapturedOutput output) {
        assertThat(get("/api/search?q=x", "carol").getStatusCode()).isEqualTo(HttpStatus.OK);

        pauseRedis();
        ResponseEntity<String> rejected;
        try {
            rejected = get("/api/search?q=x", "carol");
        } finally {
            unpauseRedis();
        }

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(output.getOut()).contains("Redis unavailable, failing CLOSED (requests rejected)");

        // A user who was never involved in the outage is served normally again
        assertThat(get("/api/search?q=x", "dave").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
