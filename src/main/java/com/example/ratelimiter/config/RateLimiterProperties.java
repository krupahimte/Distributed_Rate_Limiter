package com.example.ratelimiter.config;

import com.example.ratelimiter.core.Algorithm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Global rate limiter settings bound from the {@code rate-limiter.*} keys.
 * Per-endpoint limits (capacity, refill rate) live on {@code @RateLimit} instead.
 *
 * @param enabled   master switch; when false every request is allowed
 * @param algorithm which algorithm implementation is used for all endpoints
 * @param failOpen  when Redis is unreachable: true = allow the request, false = reject it
 * @param keyPrefix prefix for every Redis key, so limiter keys never collide with other data
 */
@Validated
@ConfigurationProperties(prefix = "rate-limiter")
public record RateLimiterProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("TOKEN_BUCKET") Algorithm algorithm,
        @DefaultValue("true") boolean failOpen,
        @NotBlank @DefaultValue("rate_limit") String keyPrefix) {
}
