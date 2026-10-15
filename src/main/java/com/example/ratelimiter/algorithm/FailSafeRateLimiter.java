package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimitResult;
import com.example.ratelimiter.core.RateLimitRule;
import com.example.ratelimiter.core.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decorator that keeps the API responding when Redis is unavailable.
 *
 * <p>Redis failures (connection refused, timeouts, script errors) arrive as Spring's
 * {@link DataAccessException}. In fail-open mode the request is allowed; in fail-closed mode it
 * is rejected. Other exceptions are bugs and are not swallowed.
 *
 * <p>While Redis is down every request fails, so the warning is logged at most once per
 * {@link #LOG_INTERVAL} with a count of suppressed failures, plus once when Redis recovers.
 */
public class FailSafeRateLimiter implements RateLimiter {

    static final Duration LOG_INTERVAL = Duration.ofSeconds(10);

    /** Retry-After sent when failing closed: Redis outages are usually short. */
    static final long FAIL_CLOSED_RETRY_AFTER_MS = 1000;

    private static final Logger log = LoggerFactory.getLogger(FailSafeRateLimiter.class);

    private final RateLimiter delegate;
    private final boolean failOpen;
    private final Clock clock;

    private final AtomicBoolean degraded = new AtomicBoolean(false);
    private final AtomicLong lastWarningAtMs = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressedFailures = new AtomicLong();
    private final AtomicLong failuresDuringOutage = new AtomicLong();

    public FailSafeRateLimiter(RateLimiter delegate, boolean failOpen, Clock clock) {
        this.delegate = delegate;
        this.failOpen = failOpen;
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryAcquire(String key, RateLimitRule rule) {
        RateLimitResult result;
        try {
            result = delegate.tryAcquire(key, rule);
        } catch (DataAccessException e) {
            onRedisFailure(e);
            return failOpen
                    ? RateLimitResult.allowed(rule.capacity(), rule.capacity())
                    : RateLimitResult.denied(rule.capacity(), FAIL_CLOSED_RETRY_AFTER_MS);
        }
        if (degraded.compareAndSet(true, false)) {
            suppressedFailures.set(0);
            log.info("Redis is reachable again, rate limiting resumed ({} failures while unavailable)",
                    failuresDuringOutage.getAndSet(0));
        }
        return result;
    }

    private void onRedisFailure(DataAccessException e) {
        degraded.set(true);
        failuresDuringOutage.incrementAndGet();
        long now = clock.millis();
        long last = lastWarningAtMs.get();
        boolean dueForWarning = last == Long.MIN_VALUE || now - last >= LOG_INTERVAL.toMillis();
        if (dueForWarning && lastWarningAtMs.compareAndSet(last, now)) {
            log.warn("Redis unavailable, failing {} ({} similar failures suppressed): {}",
                    failOpen ? "OPEN (requests allowed)" : "CLOSED (requests rejected)",
                    suppressedFailures.getAndSet(0), e.getMessage());
        } else {
            suppressedFailures.incrementAndGet();
            log.debug("Redis unavailable", e);
        }
    }

    @Override
    public Algorithm algorithm() {
        return delegate.algorithm();
    }

    public RateLimiter delegate() {
        return delegate;
    }

    public boolean failOpen() {
        return failOpen;
    }
}
