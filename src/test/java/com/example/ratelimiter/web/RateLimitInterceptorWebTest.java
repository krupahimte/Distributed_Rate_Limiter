package com.example.ratelimiter.web;

import com.example.ratelimiter.annotation.KeyType;
import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.config.RateLimiterConfig;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web slice test: real Spring MVC, interceptor and key resolver; the {@link RateLimiter} is mocked,
 * so no Redis is needed.
 */
@WebMvcTest
@Import({RateLimiterConfig.class, RateLimitKeyResolver.class, RateLimitInterceptorWebTest.TestController.class})
class RateLimitInterceptorWebTest {

    @RestController
    static class TestController {
        @RateLimit(capacity = 5, refillRate = 1, unit = ChronoUnit.MINUTES, key = KeyType.USER)
        @GetMapping("/limited")
        String limited() {
            return "ok";
        }

        @GetMapping("/unlimited")
        String unlimited() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RateLimiter rateLimiter;

    @Test
    void allowedRequestReachesControllerWithRateLimitHeaders() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(5, 4));

        mvc.perform(get("/limited").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"))
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "4"))
                .andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void passesResolvedKeyAndAnnotationRuleToLimiter() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(5, 4));

        mvc.perform(get("/limited").header("X-User-Id", "alice"));

        verify(rateLimiter).tryAcquire(
                eq("rate_limit:TestController.limited:user:alice"),
                eq(new RateLimitRule(5, 1, Duration.ofMinutes(1))));
    }

    @Test
    void rejectedRequestGets429WithRetryAfterAndProblemBody() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.denied(5, 2500));

        mvc.perform(get("/limited").header("X-User-Id", "alice"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(3))
                .andExpect(jsonPath("$.path").value("/limited"));
    }

    @Test
    void endpointsWithoutAnnotationAreNotLimited() throws Exception {
        mvc.perform(get("/unlimited"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-RateLimit-Limit"));

        verifyNoInteractions(rateLimiter);
    }
}
