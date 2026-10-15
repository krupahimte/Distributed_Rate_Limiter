package com.example.ratelimiter.web;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Browser origins allowed to call the API (the playground frontend), bound from
 * {@code app.cors.allowed-origins}. A comma-separated environment variable works too:
 * {@code APP_CORS_ALLOWED_ORIGINS=https://a.example,https://b.example}.
 *
 * @param allowedOrigins exact origins (scheme://host:port); defaults to the Vite dev server and
 *                       the docker compose frontend
 */
@Validated
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        @NotEmpty @DefaultValue({"http://localhost:5173", "http://localhost:3000"}) List<String> allowedOrigins) {
}
