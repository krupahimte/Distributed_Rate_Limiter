package com.example.ratelimiter.demo;

import com.example.ratelimiter.config.RateLimiterConfig;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import com.example.ratelimiter.web.MockLimiterRegistry;
import com.example.ratelimiter.web.RateLimitKeyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checks each demo endpoint's response and that it is wired to the intended limit and key type.
 * The limiter is mocked; real Redis behaviour is covered by the integration tests.
 */
@WebMvcTest(DemoController.class)
@Import({RateLimiterConfig.class, RateLimitKeyResolver.class, MockLimiterRegistry.class})
@TestPropertySource(properties = "app.instance-id=test-instance")
class DemoControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RateLimiter rateLimiter;

    @BeforeEach
    void allowEverything() {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(10, 9));
    }

    @Test
    void pingIsLimitedPerIpWithBurstOf10Then5PerSecond() throws Exception {
        mvc.perform(get("/api/ping").with(request -> {
                    request.setRemoteAddr("203.0.113.7");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("pong"))
                .andExpect(jsonPath("$.instance").value("test-instance"))
                .andExpect(header().exists("X-RateLimit-Remaining"));

        verify(rateLimiter).tryAcquire(
                eq("rate_limit:DemoController.ping:ip:203.0.113.7"),
                eq(new RateLimitRule(10, 5, Duration.ofSeconds(1))));
    }

    @Test
    void searchIsLimitedPerUserAt5PerMinute() throws Exception {
        mvc.perform(get("/api/search").param("q", "redis").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.query").value("redis"))
                .andExpect(jsonPath("$.results.length()").value(3));

        verify(rateLimiter).tryAcquire(
                eq("rate_limit:DemoController.search:user:alice"),
                eq(new RateLimitRule(5, 5, Duration.ofMinutes(1))));
    }

    @Test
    void reportsAreLimitedPerUserAt2ThenOnePerMinute() throws Exception {
        mvc.perform(post("/api/reports").header("X-User-Id", "bob"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.reportId").isNotEmpty());

        verify(rateLimiter).tryAcquire(
                eq("rate_limit:DemoController.createReport:user:bob"),
                eq(new RateLimitRule(2, 1, Duration.ofMinutes(1))));
    }

    @Test
    void deniedRequestNeverReachesController() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.denied(2, 60_000));

        mvc.perform(post("/api/reports").header("X-User-Id", "bob"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.reportId").doesNotExist());
    }
}
