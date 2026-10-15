package com.example.ratelimiter.web;

import com.example.ratelimiter.annotation.KeyType;
import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.config.RateLimiterProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

/**
 * Builds the Redis key identifying who is calling which limit:
 * {@code <prefix>:<limit name>:user:<id>} or {@code <prefix>:<limit name>:ip:<address>}.
 *
 * <p>The client IP comes from {@link HttpServletRequest#getRemoteAddr()}. With
 * {@code server.forward-headers-strategy=native}, Tomcat replaces it with the
 * {@code X-Forwarded-For} client only when the request came through a trusted internal proxy,
 * so callers cannot pick their own IP by sending the header themselves.
 */
@Component
public class RateLimitKeyResolver {

    public static final String USER_ID_HEADER = "X-User-Id";

    private static final Logger log = LoggerFactory.getLogger(RateLimitKeyResolver.class);

    /** Keeps user ids short and free of characters that could break the key format. */
    private static final Pattern VALID_USER_ID = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    private final String keyPrefix;

    public RateLimitKeyResolver(RateLimiterProperties properties) {
        this.keyPrefix = properties.keyPrefix();
    }

    public String resolve(HttpServletRequest request, RateLimit rateLimit, Method method) {
        return keyPrefix + ":" + limitName(rateLimit, method) + ":" + identity(request, rateLimit.key());
    }

    private static String limitName(RateLimit rateLimit, Method method) {
        if (!rateLimit.name().isBlank()) {
            return rateLimit.name();
        }
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }

    /**
     * USER limits fall back to the client IP when the header is missing or malformed,
     * so anonymous callers are still limited instead of sharing one bucket or bypassing it.
     */
    private static String identity(HttpServletRequest request, KeyType keyType) {
        if (keyType == KeyType.USER) {
            String userId = request.getHeader(USER_ID_HEADER);
            if (userId != null && VALID_USER_ID.matcher(userId).matches()) {
                return "user:" + userId;
            }
            log.debug("Missing or invalid {} header, limiting by IP instead", USER_ID_HEADER);
        }
        return "ip:" + request.getRemoteAddr();
    }
}
