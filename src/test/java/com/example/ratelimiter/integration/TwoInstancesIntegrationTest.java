package com.example.ratelimiter.integration;

import com.example.ratelimiter.RateLimiterApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts a second, independent copy of the application against the same Redis and alternates
 * requests between the two. The limit must hold across both, as it does behind nginx.
 */
class TwoInstancesIntegrationTest extends RedisIntegrationTestSupport {

    private ConfigurableApplicationContext secondInstance;
    private String secondInstanceUrl;
    private final TestRestTemplate secondClient = new TestRestTemplate();

    @BeforeEach
    void startSecondInstance() {
        secondInstance = new SpringApplicationBuilder(RateLimiterApplication.class).run(
                "--server.port=0",
                "--app.instance-id=instance-2",
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379));
        secondInstanceUrl = "http://localhost:" + secondInstance.getEnvironment().getProperty("local.server.port");
    }

    @AfterEach
    void stopSecondInstance() {
        secondInstance.close();
    }

    @Test
    void limitIsSharedAcrossInstances() {
        int allowed = 0;
        int rejected = 0;
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> response = i % 2 == 0
                    ? get("/api/search?q=x", "alice")
                    : searchOnSecondInstance("alice");
            if (response.getStatusCode() == HttpStatus.OK) {
                allowed++;
            } else if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                rejected++;
            }
        }

        // Limit is 5 per user; each instance alone would have allowed 5
        assertThat(allowed).isEqualTo(5);
        assertThat(rejected).isEqualTo(5);
    }

    @Test
    void bothInstancesServeRequests() {
        assertThat(get("/api/search?q=x", "bob").getBody()).doesNotContain("instance-2");
        assertThat(searchOnSecondInstance("bob").getBody()).contains("\"instance\":\"instance-2\"");
    }

    private ResponseEntity<String> searchOnSecondInstance(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", userId);
        return secondClient.exchange(secondInstanceUrl + "/api/search?q=x", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
    }
}
