package com.example.ratelimiter.web;

import com.example.ratelimiter.config.RateLimiterConfig;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimiter;
import com.example.ratelimiter.demo.PlaygroundController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS behaviour the browser playground depends on, with the default allowed origins.
 */
@WebMvcTest(PlaygroundController.class)
@Import({RateLimiterConfig.class, RateLimitKeyResolver.class, MockLimiterRegistry.class})
class CorsTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final String PATH = "/api/playground/token-bucket";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RateLimiter rateLimiter;

    @Test
    void preflightIsAnsweredWithoutConsumingARateLimitToken() throws Exception {
        mvc.perform(options(PATH)
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "X-User-Id"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Headers", equalToIgnoringCase("X-User-Id")))
                .andExpect(header().string("Access-Control-Max-Age", "3600"));

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void allowedResponseExposesRateLimitHeadersToTheBrowser() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(10, 9));

        mvc.perform(get(PATH).header("Origin", ALLOWED_ORIGIN).header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-RateLimit-Limit")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("X-RateLimit-Remaining")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
    }

    @Test
    void rejected429StillCarriesCorsHeadersSoTheBrowserCanReadIt() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.denied(10, 3000));

        mvc.perform(get(PATH).header("Origin", ALLOWED_ORIGIN).header("X-User-Id", "alice"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
    }

    @Test
    void composeFrontendOriginIsAllowedByDefault() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(10, 9));

        mvc.perform(get(PATH).header("Origin", "http://localhost:3000").header("X-User-Id", "alice"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void unknownOriginIsRejectedBeforeTheRateLimiter() throws Exception {
        mvc.perform(get(PATH).header("Origin", "https://evil.example").header("X-User-Id", "alice"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

        mvc.perform(options(PATH)
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void requestsWithoutOriginAreUnaffected() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(10, 9));

        // curl, k6 and server-to-server callers send no Origin header
        mvc.perform(get(PATH).header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
