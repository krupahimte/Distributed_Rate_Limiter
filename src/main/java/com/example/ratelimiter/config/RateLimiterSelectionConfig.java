package com.example.ratelimiter.config;

import com.example.ratelimiter.algorithm.FailSafeRateLimiter;
import com.example.ratelimiter.algorithm.RateLimiterRegistry;
import com.example.ratelimiter.algorithm.SlidingWindowCounterRateLimiter;
import com.example.ratelimiter.algorithm.SlidingWindowLogRateLimiter;
import com.example.ratelimiter.algorithm.TokenBucketRateLimiter;
import com.example.ratelimiter.core.Algorithm;
import com.example.ratelimiter.core.RateLimiter;
import io.lettuce.core.ClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientOptionsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps every algorithm with fail-open/fail-closed handling and makes the one configured in
 * {@code rate-limiter.algorithm} the default. Endpoints may pick another with
 * {@code @RateLimit(algorithm = ...)}.
 */
@Configuration
public class RateLimiterSelectionConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterSelectionConfig.class);

    @Bean
    public RateLimiterRegistry rateLimiterRegistry(TokenBucketRateLimiter tokenBucket,
                                                   SlidingWindowLogRateLimiter slidingWindowLog,
                                                   SlidingWindowCounterRateLimiter slidingWindowCounter,
                                                   RateLimiterProperties properties) {
        List<RateLimiter> candidates = List.of(tokenBucket, slidingWindowLog, slidingWindowCounter);
        Map<Algorithm, FailSafeRateLimiter> limiters = new EnumMap<>(Algorithm.class);
        for (Algorithm algorithm : Algorithm.values()) {
            limiters.put(algorithm, new FailSafeRateLimiter(
                    select(algorithm, candidates), properties.failOpen(), Clock.systemUTC()));
        }
        log.info("Rate limiting with {} (fail-{} when Redis is unavailable)",
                properties.algorithm(), properties.failOpen() ? "open" : "closed");
        return new RateLimiterRegistry(limiters, properties.algorithm());
    }

    /**
     * The default limiter (the configured algorithm). {@code @Primary} makes it win over the three
     * algorithm beans wherever a {@link RateLimiter} is injected.
     */
    @Bean
    @Primary
    public RateLimiter rateLimiter(RateLimiterRegistry registry) {
        return registry.defaultLimiter();
    }

    static RateLimiter select(Algorithm algorithm, Collection<? extends RateLimiter> candidates) {
        List<? extends RateLimiter> matches = candidates.stream()
                .filter(candidate -> candidate.algorithm() == algorithm)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one RateLimiter for " + algorithm + " but found " + matches.size());
        }
        return matches.get(0);
    }

    /**
     * Fail fast while Redis is down instead of queueing commands until they time out, so
     * fail-open/fail-closed decisions happen immediately. Lettuce keeps reconnecting in the background.
     */
    @Bean
    public LettuceClientOptionsBuilderCustomizer rejectCommandsWhileDisconnected() {
        return builder -> builder.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
    }
}
