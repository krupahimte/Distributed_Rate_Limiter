package com.example.ratelimiter.web;

import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces {@link RateLimit} on controller methods before they run.
 *
 * <p>Every limited response carries {@code X-RateLimit-Limit} and {@code X-RateLimit-Remaining};
 * rejected requests get HTTP 429, a {@code Retry-After} header (seconds) and an RFC 7807
 * problem+json body.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String LIMIT_HEADER = "X-RateLimit-Limit";
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final RateLimiter rateLimiter;
    private final RateLimitKeyResolver keyResolver;
    private final RateLimiterProperties properties;
    private final ObjectMapper objectMapper;

    /** Annotation values never change at runtime, so each method's rule is built once. */
    private final Map<Method, RateLimitRule> rules = new ConcurrentHashMap<>();

    public RateLimitInterceptor(RateLimiter rateLimiter, RateLimitKeyResolver keyResolver,
                                RateLimiterProperties properties, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!properties.enabled() || !(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;
        }

        Method method = handlerMethod.getMethod();
        RateLimitRule rule = rules.computeIfAbsent(method, m -> RateLimitRule.from(rateLimit));
        String key = keyResolver.resolve(request, rateLimit, method);
        RateLimitResult result = rateLimiter.tryAcquire(key, rule);

        response.setHeader(LIMIT_HEADER, Long.toString(result.limit()));
        response.setHeader(REMAINING_HEADER, Long.toString(result.remaining()));
        if (result.allowed()) {
            return true;
        }

        reject(request, response, result);
        return false;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, RateLimitResult result)
            throws IOException {
        long retryAfterSeconds = result.retryAfterSeconds();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Retry after " + retryAfterSeconds + " second(s).");
        problem.setProperty("path", request.getRequestURI());
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
