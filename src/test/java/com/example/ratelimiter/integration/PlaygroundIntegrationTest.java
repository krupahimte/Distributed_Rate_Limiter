package com.example.ratelimiter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The playground endpoints against real Redis. The configured default is the sliding window
 * counter, to prove each playground endpoint uses its own algorithm regardless of the default.
 */
@TestPropertySource(properties = "rate-limiter.algorithm=sliding-window-counter")
class PlaygroundIntegrationTest extends RedisIntegrationTestSupport {

    private static final String TOKEN_BUCKET = "/api/playground/token-bucket";
    private static final String SLIDING_WINDOW_LOG = "/api/playground/sliding-window-log";
    private static final String SLIDING_WINDOW_COUNTER = "/api/playground/sliding-window-counter";

    @LocalServerPort
    private int port;

    @Test
    void eachEndpointUsesItsOwnAlgorithmRegardlessOfConfiguredDefault() {
        get(TOKEN_BUCKET, "alice");
        get(SLIDING_WINDOW_LOG, "alice");
        get(SLIDING_WINDOW_COUNTER, "alice");
        get("/api/search?q=x", "alice");

        assertThat(redis.keys("rate_limit:*")).containsExactlyInAnyOrder(
                "rate_limit:PlaygroundController.tokenBucket:user:alice:tb",
                "rate_limit:PlaygroundController.slidingWindowLog:user:alice:swl",
                "rate_limit:PlaygroundController.slidingWindowCounter:user:alice:swc",
                // a demo endpoint without override follows the configured default
                "rate_limit:DemoController.search:user:alice:swc");
    }

    @ParameterizedTest
    @ValueSource(strings = {TOKEN_BUCKET, SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER})
    void sameLimitOnEveryAlgorithm(String path) {
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> response = get(path, "bob");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("10");
            assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo(Integer.toString(9 - i));
        }

        assertThat(get(path, "bob").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void tokenBucketRefillsGraduallyWhileSlidingLogWaitsForItsWindow() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            get(TOKEN_BUCKET, "carol");
            get(SLIDING_WINDOW_LOG, "carol");
        }
        assertThat(get(TOKEN_BUCKET, "carol").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(get(SLIDING_WINDOW_LOG, "carol").getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // 2 tokens per second: after 700 ms the bucket has earned at least one token,
        // but the log's oldest request is still inside its 5 s window
        Thread.sleep(700);

        assertThat(get(TOKEN_BUCKET, "carol").getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> log = get(SLIDING_WINDOW_LOG, "carol");
        assertThat(log.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(log.getHeaders().getFirst("Retry-After"))).isBetween(4L, 5L);
    }

    @ParameterizedTest
    @ValueSource(strings = {SLIDING_WINDOW_LOG, SLIDING_WINDOW_COUNTER})
    void windowEndpointsAdmitExactlyTheLimitUnderParallelRequests(String path) {
        assertThat(fireInParallel(path, "dave", 60).allowed()).isEqualTo(10);
    }

    @Test
    void tokenBucketAdmitsTheLimitPlusOnlyWhatRefilledDuringParallelRequests() {
        Burst burst = fireInParallel(TOKEN_BUCKET, "erin", 60);

        // 2 tokens per second may legitimately arrive while the burst is in flight
        long refilled = (long) Math.floor(burst.elapsed().toMillis() * 2 / 1000.0);
        assertThat(burst.allowed()).isBetween(10L, 10 + refilled);
    }

    @Test
    void catalogReportsOverridesAndConfiguredDefault() {
        String catalog = rest.getForObject("/api/playground/endpoints", String.class);

        assertThat(catalog)
                .contains("\"path\":\"/api/playground/token-bucket\",\"capacity\":10,\"refillRate\":2,"
                        + "\"refillPeriodMs\":1000,\"key\":\"USER\",\"algorithm\":\"TOKEN_BUCKET\","
                        + "\"algorithmOverridden\":true")
                .contains("\"path\":\"/api/search\",\"capacity\":5,\"refillRate\":5,"
                        + "\"refillPeriodMs\":60000,\"key\":\"USER\",\"algorithm\":\"SLIDING_WINDOW_COUNTER\","
                        + "\"algorithmOverridden\":false");
    }

    private record Burst(long allowed, Duration elapsed) {
    }

    private Burst fireInParallel(String path, String userId, int requests) {
        HttpClient client = HttpClient.newHttpClient();
        URI uri = URI.create("http://localhost:" + port + path);
        long start = System.nanoTime();
        List<CompletableFuture<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            HttpRequest request = HttpRequest.newBuilder(uri).header("X-User-Id", userId).build();
            statuses.add(client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenApply(HttpResponse::statusCode));
        }
        long allowed = statuses.stream().map(CompletableFuture::join).filter(status -> status == 200).count();
        return new Burst(allowed, Duration.ofNanos(System.nanoTime() - start));
    }
}
