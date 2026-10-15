package com.example.ratelimiter.config;

import com.example.ratelimiter.algorithm.FailSafeRateLimiter;
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
import java.util.List;

/**
 * Chooses the algorithm configured in {@code rate-limiter.algorithm} and wraps it with
 * fail-open/fail-closed handling.
 */
@Configuration
public class RateLimiterSelectionConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterSelectionConfig.class);

    /**
     * The limiter the rest of the app uses. {@code @Primary} makes it win over the three
     * algorithm beans wherever a {@link RateLimiter} is injected.
     */
    @Bean
    @Primary
    public FailSafeRateLimiter rateLimiter(TokenBucketRateLimiter tokenBucket,
                                           SlidingWindowLogRateLimiter slidingWindowLog,
                                           SlidingWindowCounterRateLimiter slidingWindowCounter,
                                           RateLimiterProperties properties) {
        RateLimiter selected = select(properties.algorithm(),
                List.of(tokenBucket, slidingWindowLog, slidingWindowCounter));
        log.info("Rate limiting with {} (fail-{} when Redis is unavailable)",
                selected.algorithm(), properties.failOpen() ? "open" : "closed");
        return new FailSafeRateLimiter(selected, properties.failOpen(), Clock.systemUTC());
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
