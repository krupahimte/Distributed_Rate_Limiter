package com.example.ratelimiter.web;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final CorsProperties corsProperties;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor, CorsProperties corsProperties) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.corsProperties = corsProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor);
    }

    /**
     * Lets the playground frontend call the API from another origin. Browsers hide response
     * headers from JavaScript unless they are listed in Access-Control-Expose-Headers, so the
     * rate limit headers are exposed explicitly.
     *
     * <p>Preflight (OPTIONS) requests are answered by Spring before the rate limit interceptor
     * sees a controller method, so they never consume a token. Spring's CORS handling also runs
     * before the interceptor, so 429 responses still carry the CORS headers the browser needs.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsProperties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST")
                .allowedHeaders(RateLimitKeyResolver.USER_ID_HEADER, HttpHeaders.CONTENT_TYPE)
                .exposedHeaders(RateLimitInterceptor.LIMIT_HEADER, RateLimitInterceptor.REMAINING_HEADER,
                        HttpHeaders.RETRY_AFTER)
                .maxAge(3600);
    }
}
