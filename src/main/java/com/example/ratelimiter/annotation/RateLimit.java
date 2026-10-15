package com.example.ratelimiter.annotation;

import com.example.ratelimiter.core.Algorithm;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.temporal.ChronoUnit;

/**
 * Rate limits a controller method.
 *
 * <p>{@code capacity} is the most requests a caller can make in a burst, and
 * {@code refillRate} tokens are restored every {@code refillPeriod} {@code unit}s.
 * Every algorithm enforces the same sustained rate; they differ only in how bursts
 * are handled. The window-based algorithms use a window of
 * {@code capacity / refillRate * refillPeriod}.
 *
 * <pre>{@code
 * // Up to 10 requests at once, then 10 more per minute, per user
 * @RateLimit(capacity = 10, refillRate = 10, refillPeriod = 1, unit = ChronoUnit.MINUTES, key = KeyType.USER)
 *
 * // Same, but always using the sliding window log regardless of rate-limiter.algorithm
 * @RateLimit(capacity = 10, refillRate = 10, unit = ChronoUnit.MINUTES, algorithm = Algorithm.SLIDING_WINDOW_LOG)
 * }</pre>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** Maximum burst size (token bucket) or requests per window (window algorithms). */
    long capacity();

    /** Tokens restored every {@link #refillPeriod()}. */
    long refillRate();

    /** Length of a refill period, in {@link #unit()}. */
    long refillPeriod() default 1;

    ChronoUnit unit() default ChronoUnit.SECONDS;

    KeyType key() default KeyType.IP;

    /**
     * Name of the limit, used in the Redis key. Endpoints that share a name share a limit.
     * Defaults to {@code ClassName.methodName}.
     */
    String name() default "";

    /**
     * Algorithm for this endpoint only. Leave empty (the default) to use {@code rate-limiter.algorithm}
     * from application.yml. At most one value; it is an array only because annotation attributes
     * cannot default to {@code null}.
     */
    Algorithm[] algorithm() default {};
}
