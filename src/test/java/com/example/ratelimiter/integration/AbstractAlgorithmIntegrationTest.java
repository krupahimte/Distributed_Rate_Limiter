package com.example.ratelimiter.integration;

import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end behaviour every algorithm must share, run once per algorithm by the subclasses.
 * Uses the demo endpoints: /api/search (5 per user), /api/reports (2 per user), /api/ping (10 per IP).
 */
abstract class AbstractAlgorithmIntegrationTest extends RedisIntegrationTestSupport {

    @Autowired
    private RateLimiter rateLimiter;

    abstract Algorithm expectedAlgorithm();

    abstract String expectedKeySuffix();

    @Test
    void usesConfiguredAlgorithm() {
        assertThat(rateLimiter.algorithm()).isEqualTo(expectedAlgorithm());

        get("/api/search?q=x", "alice");

        assertThat(redis.keys("rate_limit:*"))
                .containsExactly("rate_limit:DemoController.search:user:alice" + expectedKeySuffix());
    }

    @Test
    void allowsExactlyCapacityThenRejectsWith429AndHeaders() {
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> response = get("/api/search?q=x", "alice");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("5");
            assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo(Integer.toString(4 - i));
        }

        ResponseEntity<String> rejected = get("/api/search?q=x", "alice");

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("5");
        assertThat(rejected.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(Long.parseLong(rejected.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        assertThat(rejected.getBody()).contains("\"status\":429").contains("Too Many Requests");
    }

    @Test
    void eachUserHasTheirOwnLimit() {
        assertThat(post("/api/reports", "alice").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post("/api/reports", "alice").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(post("/api/reports", "alice").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(post("/api/reports", "bob").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void endpointsHaveSeparateLimits() {
        post("/api/reports", "alice");
        post("/api/reports", "alice");
        assertThat(post("/api/reports", "alice").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(get("/api/search?q=x", "alice").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ipLimitedEndpointUsesForwardedClientIp() {
        // Burst of 10 refilled at 5/s: 20 quick requests must hit the limit
        boolean limited = false;
        for (int i = 0; i < 20 && !limited; i++) {
            limited = getFromIp("/api/ping", "203.0.113.1").getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
        }
        assertThat(limited).isTrue();

        ResponseEntity<String> otherClient = getFromIp("/api/ping", "203.0.113.2");
        assertThat(otherClient.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherClient.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("9");
    }

    @Test
    void endpointsWithoutRateLimitAreUntouched() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().containsKey("X-RateLimit-Limit")).isFalse();
        assertThat(redis.keys("rate_limit:*")).isEmpty();
    }

    @Test
    void everyRateLimitKeyExpires() {
        get("/api/search?q=x", "alice");
        post("/api/reports", "bob");
        getFromIp("/api/ping", "203.0.113.9");

        Set<String> keys = redis.keys("rate_limit:*");
        assertThat(keys).hasSize(3);
        assertThat(keys).allSatisfy(key ->
                assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive());
    }
}
