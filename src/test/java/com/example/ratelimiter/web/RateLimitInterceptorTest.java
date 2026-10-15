package com.example.ratelimiter.web;

import com.example.ratelimiter.algorithm.RateLimiterRegistry;
import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the interceptor paths that the web slice test does not reach.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private RateLimiter rateLimiter;

    @Mock
    private RateLimiter slidingWindowLog;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    static class Endpoints {
        @RateLimit(capacity = 3, refillRate = 1)
        public String limited() {
            return "ok";
        }

        @RateLimit(capacity = 3, refillRate = 1, algorithm = Algorithm.SLIDING_WINDOW_LOG)
        public String overridden() {
            return "ok";
        }

        @RateLimit(capacity = 3, refillRate = 1,
                algorithm = {Algorithm.SLIDING_WINDOW_LOG, Algorithm.TOKEN_BUCKET})
        public String twoAlgorithms() {
            return "ok";
        }
    }

    private RateLimitInterceptor interceptor(boolean enabled) {
        RateLimiterProperties properties = new RateLimiterProperties(enabled, Algorithm.TOKEN_BUCKET, true, "rl");
        RateLimiterRegistry registry = new RateLimiterRegistry(Map.of(
                Algorithm.TOKEN_BUCKET, rateLimiter,
                Algorithm.SLIDING_WINDOW_LOG, slidingWindowLog), Algorithm.TOKEN_BUCKET);
        return new RateLimitInterceptor(registry, new RateLimitKeyResolver(properties), properties,
                new ObjectMapper());
    }

    private static HandlerMethod limitedHandler() throws NoSuchMethodException {
        return handler("limited");
    }

    private static HandlerMethod handler(String methodName) throws NoSuchMethodException {
        return new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(methodName));
    }

    @Test
    void usesConfiguredDefaultAlgorithmWhenAnnotationHasNoOverride() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(3, 2));

        interceptor(true).preHandle(request, response, limitedHandler());

        verify(rateLimiter).tryAcquire(anyString(), any());
        verifyNoInteractions(slidingWindowLog);
    }

    @Test
    void usesAlgorithmOverrideFromAnnotation() throws Exception {
        when(slidingWindowLog.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(3, 2));

        interceptor(true).preHandle(request, response, handler("overridden"));

        verify(slidingWindowLog).tryAcquire(anyString(), any());
        verifyNoInteractions(rateLimiter);
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("2");
    }

    @Test
    void rejectsMoreThanOneAlgorithmOnAnEndpoint() {
        RateLimitInterceptor interceptor = interceptor(true);

        assertThatIllegalStateException()
                .isThrownBy(() -> interceptor.preHandle(request, response, handler("twoAlgorithms")))
                .withMessageContaining("at most one algorithm");
    }

    @Test
    void disabledLimiterLetsEverythingThrough() throws Exception {
        assertThat(interceptor(false).preHandle(request, response, limitedHandler())).isTrue();

        verifyNoInteractions(rateLimiter);
        assertThat(response.getHeader("X-RateLimit-Limit")).isNull();
    }

    @Test
    void nonControllerHandlersAreIgnored() throws Exception {
        assertThat(interceptor(true).preHandle(request, response, new ResourceHttpRequestHandler())).isTrue();

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void ipLimitedRequestUsesRemoteAddressAndStopsChainWhenDenied() throws Exception {
        request.setRemoteAddr("203.0.113.7");
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.denied(3, 400));

        boolean proceed = interceptor(true).preHandle(request, response, limitedHandler());

        assertThat(proceed).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        verify(rateLimiter).tryAcquire(any(), any());
        assertThat(response.getContentAsString()).contains("Rate limit exceeded");
    }

    @Test
    void buildsRuleOnceAndReusesItForRepeatedRequests() throws Exception {
        when(rateLimiter.tryAcquire(anyString(), any())).thenReturn(RateLimitResult.allowed(3, 2));
        RateLimitInterceptor interceptor = interceptor(true);

        interceptor.preHandle(request, response, limitedHandler());
        interceptor.preHandle(request, new MockHttpServletResponse(), limitedHandler());

        ArgumentCaptor<RateLimitRule> rules = ArgumentCaptor.forClass(RateLimitRule.class);
        verify(rateLimiter, times(2)).tryAcquire(anyString(), rules.capture());
        assertThat(rules.getAllValues().get(0)).isSameAs(rules.getAllValues().get(1));
    }
}
