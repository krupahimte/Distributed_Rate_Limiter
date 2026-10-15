package com.example.ratelimiter.integration;

import com.example.ratelimiter.RateLimiterApplication;
import com.example.ratelimiter.annotation.KeyType;
import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.core.Algorithm;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.example.ratelimiter.integration.RedisIntegrationTestSupport.REDIS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires 1000 simultaneous requests for one user, split across two application instances that
 * share one Redis, and asserts that exactly the configured limit is admitted. Any race between
 * reading and updating the limiter state (in one instance or across both) would admit more.
 */
@Testcontainers(disabledWithoutDocker = true)
class ConcurrencyIntegrationTest {

    private static final int REQUESTS = 1000;
    private static final int LIMIT = 100;

    /**
     * Test-only endpoint: 100 requests, refilled at 100 per hour, so during a test lasting a
     * few seconds no extra request can legitimately be admitted.
     */
    @RestController
    static class LoadTestController {
        @RateLimit(capacity = LIMIT, refillRate = LIMIT, unit = ChronoUnit.HOURS, key = KeyType.USER,
                name = "load-test")
        @GetMapping("/load-test")
        String hit() {
            return "ok";
        }
    }

    @BeforeAll
    static void startRedis() {
        REDIS.start();
    }

    @ParameterizedTest
    @EnumSource(Algorithm.class)
    void admitsExactlyTheLimitUnder1000ConcurrentRequestsAcrossTwoInstances(Algorithm algorithm)
            throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");

        try (ConfigurableApplicationContext first = startInstance(algorithm, "instance-1");
             ConfigurableApplicationContext second = startInstance(algorithm, "instance-2")) {
            List<URI> targets = List.of(loadTestUri(first), loadTestUri(second));

            Map<Integer, Long> statusCounts = fireConcurrently(targets);

            assertThat(statusCounts)
                    .as("HTTP status counts for %s", algorithm)
                    .containsOnlyKeys(200, 429)
                    .containsEntry(200, (long) LIMIT)
                    .containsEntry(429, (long) (REQUESTS - LIMIT));
        }
    }

    /** Sends all requests without waiting for responses, alternating between the instances. */
    private static Map<Integer, Long> fireConcurrently(List<URI> targets) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        List<CompletableFuture<Integer>> responses = new ArrayList<>(REQUESTS);
        for (int i = 0; i < REQUESTS; i++) {
            HttpRequest request = HttpRequest.newBuilder(targets.get(i % targets.size()))
                    .header("X-User-Id", "load-tester")
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            responses.add(client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(HttpResponse::statusCode));
        }

        return responses.stream()
                .map(CompletableFuture::join)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static ConfigurableApplicationContext startInstance(Algorithm algorithm, String instanceId) {
        return new SpringApplicationBuilder(RateLimiterApplication.class, LoadTestController.class).run(
                "--server.port=0",
                "--app.instance-id=" + instanceId,
                "--rate-limiter.algorithm=" + algorithm,
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                // Tomcat queues only 100 pending connections by default; the burst opens 500 per
                // instance at once, and refused connections would never reach the limiter
                "--server.tomcat.accept-count=" + REQUESTS,
                "--logging.level.root=WARN");
    }

    private static URI loadTestUri(ConfigurableApplicationContext context) {
        return URI.create("http://localhost:" + context.getEnvironment().getProperty("local.server.port")
                + "/load-test");
    }
}
