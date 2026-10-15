package com.example.ratelimiter.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CORS on the real server (Tomcat, real Redis) with a custom origin list, including a 429 and a
 * check that preflights do not use up the limit.
 */
@TestPropertySource(properties = "app.cors.allowed-origins=https://playground.example")
class CorsIntegrationTest extends RedisIntegrationTestSupport {

    private static final String ORIGIN = "https://playground.example";
    private static final String PATH = "/api/playground/sliding-window-log";

    @Test
    void preflightsDoNotConsumeTokensAnd429IsReadableByTheBrowser() {
        for (int i = 0; i < 20; i++) {
            assertThat(preflight().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        assertThat(redis.keys("rate_limit:*")).isEmpty();

        for (int i = 0; i < 10; i++) {
            assertThat(getWithOrigin().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        ResponseEntity<String> rejected = getWithOrigin();

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rejected.getHeaders().getAccessControlAllowOrigin()).isEqualTo(ORIGIN);
        assertThat(rejected.getHeaders().getAccessControlExposeHeaders())
                .contains("X-RateLimit-Limit", "X-RateLimit-Remaining", "Retry-After");
        assertThat(rejected.getHeaders().getFirst("Retry-After")).isNotBlank();
    }

    @Test
    void defaultOriginsAreReplacedByTheConfiguredList() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:5173");
        headers.set("X-User-Id", "bob");

        ResponseEntity<String> response = rest.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<String> preflight() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(ORIGIN);
        headers.setAccessControlRequestMethod(HttpMethod.GET);
        headers.set("Access-Control-Request-Headers", "x-user-id");
        return rest.exchange(PATH, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> getWithOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(ORIGIN);
        headers.set("X-User-Id", "alice");
        return rest.exchange(PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }
}
