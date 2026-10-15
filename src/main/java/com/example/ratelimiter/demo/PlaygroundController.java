package com.example.ratelimiter.demo;

import com.example.ratelimiter.algorithm.RateLimiterRegistry;
import com.example.ratelimiter.annotation.KeyType;
import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.core.Algorithm;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Endpoints for the frontend playground.
 *
 * <p>The three algorithm endpoints share one limit (burst of 10, then 2 per second, per user) and
 * differ only in algorithm, so sending them the same burst shows how each algorithm behaves.
 * {@code /endpoints} lists every rate-limited endpoint so the UI never hard-codes limits.
 */
@RestController
@RequestMapping("/api/playground")
public class PlaygroundController {

    static final long CAPACITY = 10;
    static final long REFILL_PER_SECOND = 2;

    private final String instanceId;
    private final RateLimiterRegistry registry;
    private final RequestMappingHandlerMapping handlerMapping;

    public PlaygroundController(@Value("${app.instance-id:${HOSTNAME:local}}") String instanceId,
                                RateLimiterRegistry registry,
                                @Qualifier("requestMappingHandlerMapping")
                                RequestMappingHandlerMapping handlerMapping) {
        this.instanceId = instanceId;
        this.registry = registry;
        this.handlerMapping = handlerMapping;
    }

    @RateLimit(capacity = CAPACITY, refillRate = REFILL_PER_SECOND, key = KeyType.USER,
            algorithm = Algorithm.TOKEN_BUCKET)
    @GetMapping("/token-bucket")
    public PlaygroundResponse tokenBucket() {
        return respond(Algorithm.TOKEN_BUCKET);
    }

    @RateLimit(capacity = CAPACITY, refillRate = REFILL_PER_SECOND, key = KeyType.USER,
            algorithm = Algorithm.SLIDING_WINDOW_LOG)
    @GetMapping("/sliding-window-log")
    public PlaygroundResponse slidingWindowLog() {
        return respond(Algorithm.SLIDING_WINDOW_LOG);
    }

    @RateLimit(capacity = CAPACITY, refillRate = REFILL_PER_SECOND, key = KeyType.USER,
            algorithm = Algorithm.SLIDING_WINDOW_COUNTER)
    @GetMapping("/sliding-window-counter")
    public PlaygroundResponse slidingWindowCounter() {
        return respond(Algorithm.SLIDING_WINDOW_COUNTER);
    }

    /** Every endpoint carrying {@code @RateLimit}, with its limit and effective algorithm. */
    @GetMapping("/endpoints")
    public List<EndpointInfo> endpoints() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().hasMethodAnnotation(RateLimit.class))
                .flatMap(entry -> describe(entry.getKey(), entry.getValue()).stream())
                .sorted(Comparator.comparing(EndpointInfo::path).thenComparing(EndpointInfo::method))
                .toList();
    }

    private List<EndpointInfo> describe(RequestMappingInfo mapping, HandlerMethod handler) {
        RateLimit rateLimit = handler.getMethodAnnotation(RateLimit.class);
        Algorithm[] override = rateLimit.algorithm();
        Algorithm algorithm = override.length == 0 ? registry.defaultAlgorithm() : override[0];
        long refillPeriodMs = Duration.of(rateLimit.refillPeriod(), rateLimit.unit()).toMillis();
        Set<String> methods = mapping.getMethodsCondition().getMethods().isEmpty()
                ? Set.of("GET")
                : mapping.getMethodsCondition().getMethods().stream().map(Enum::name)
                        .collect(Collectors.toSet());

        return mapping.getPatternValues().stream()
                .flatMap(path -> methods.stream().map(method -> new EndpointInfo(
                        method, path, rateLimit.capacity(), rateLimit.refillRate(), refillPeriodMs,
                        rateLimit.key(), algorithm, override.length > 0)))
                .toList();
    }

    private PlaygroundResponse respond(Algorithm algorithm) {
        return new PlaygroundResponse(algorithm, instanceId, Instant.now());
    }

    public record PlaygroundResponse(Algorithm algorithm, String instance, Instant timestamp) {
    }

    /**
     * @param algorithmOverridden true if the endpoint sets its own algorithm, false if it follows
     *                            {@code rate-limiter.algorithm}
     */
    public record EndpointInfo(String method, String path, long capacity, long refillRate,
                               long refillPeriodMs, KeyType key, Algorithm algorithm,
                               boolean algorithmOverridden) {
    }
}
