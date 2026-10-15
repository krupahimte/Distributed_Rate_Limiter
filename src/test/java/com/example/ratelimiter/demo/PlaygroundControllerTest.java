package com.example.ratelimiter.demo;

import com.example.ratelimiter.config.RateLimiterConfig;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import com.example.ratelimiter.web.MockLimiterRegistry;
import com.example.ratelimiter.web.RateLimitKeyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web slice test for the playground endpoints and the endpoint catalog. Which limiter each endpoint
 * really uses against Redis is covered by {@code PlaygroundIntegrationTest}.
 */
@WebMvcTest({PlaygroundController.class, DemoController.class})
@Import({RateLimiterConfig.class, RateLimitKeyResolver.class, MockLimiterRegistry.class})
class PlaygroundControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RateLimiter rateLimiter;

    @BeforeEach
    void allowEverything() {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(10, 9));
    }

    @ParameterizedTest
    @CsvSource({
            "token-bucket,           TOKEN_BUCKET,           PlaygroundController.tokenBucket",
            "sliding-window-log,     SLIDING_WINDOW_LOG,     PlaygroundController.slidingWindowLog",
            "sliding-window-counter, SLIDING_WINDOW_COUNTER, PlaygroundController.slidingWindowCounter"
    })
    void algorithmEndpointsShareOneLimitPerUser(String path, String algorithm, String limitName) throws Exception {
        mvc.perform(get("/api/playground/" + path).header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value(algorithm))
                .andExpect(header().string("X-RateLimit-Remaining", "9"));

        verify(rateLimiter).tryAcquire(
                eq("rate_limit:" + limitName + ":user:alice"),
                eq(new RateLimitRule(10, 2, Duration.ofSeconds(1))));
    }

    @Test
    void catalogListsEveryRateLimitedEndpointWithEffectiveAlgorithm() throws Exception {
        mvc.perform(get("/api/playground/endpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].path").value(contains(
                        "/api/ping",
                        "/api/playground/sliding-window-counter",
                        "/api/playground/sliding-window-log",
                        "/api/playground/token-bucket",
                        "/api/reports",
                        "/api/search")))
                // /api/ping follows the configured default (MockLimiterRegistry: TOKEN_BUCKET)
                .andExpect(jsonPath("$[0].method").value("GET"))
                .andExpect(jsonPath("$[0].capacity").value(10))
                .andExpect(jsonPath("$[0].refillRate").value(5))
                .andExpect(jsonPath("$[0].refillPeriodMs").value(1000))
                .andExpect(jsonPath("$[0].key").value("IP"))
                .andExpect(jsonPath("$[0].algorithm").value("TOKEN_BUCKET"))
                .andExpect(jsonPath("$[0].algorithmOverridden").value(false))
                // playground endpoints set their own algorithm
                .andExpect(jsonPath("$[2].algorithm").value("SLIDING_WINDOW_LOG"))
                .andExpect(jsonPath("$[2].algorithmOverridden").value(true))
                .andExpect(jsonPath("$[2].key").value("USER"))
                .andExpect(jsonPath("$[2].capacity").value(10))
                .andExpect(jsonPath("$[2].refillRate").value(2))
                .andExpect(jsonPath("$[4].method").value("POST"))
                .andExpect(jsonPath("$[4].refillPeriodMs").value(60_000));
    }

    @Test
    void catalogItselfIsNotRateLimited() throws Exception {
        mvc.perform(get("/api/playground/endpoints"))
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));

        verifyNoInteractions(rateLimiter);
    }
}
