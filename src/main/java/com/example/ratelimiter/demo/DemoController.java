package com.example.ratelimiter.demo;

import com.example.ratelimiter.annotation.KeyType;
import com.example.ratelimiter.annotation.RateLimit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Demo endpoints with three different limits. Every response names the instance that served it,
 * which shows in the docker-compose setup that both instances share the same limits.
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final String instanceId;

    public DemoController(@Value("${app.instance-id:${HOSTNAME:local}}") String instanceId) {
        this.instanceId = instanceId;
    }

    /** Cheap public endpoint: generous per-IP limit, bursts of 10, then 5 per second. */
    @RateLimit(capacity = 10, refillRate = 5, key = KeyType.IP)
    @GetMapping("/ping")
    public PingResponse ping() {
        return new PingResponse("pong", instanceId, Instant.now());
    }

    /** Typical per-user API call: 5 at once, then 5 per minute. */
    @RateLimit(capacity = 5, refillRate = 5, unit = ChronoUnit.MINUTES, key = KeyType.USER)
    @GetMapping("/search")
    public SearchResponse search(@RequestParam(defaultValue = "") String q) {
        List<String> results = q.isBlank() ? List.of() : List.of(q + " #1", q + " #2", q + " #3");
        return new SearchResponse(q, results, instanceId);
    }

    /** Expensive operation: strict per-user limit of 2, then 1 per minute. */
    @RateLimit(capacity = 2, refillRate = 1, unit = ChronoUnit.MINUTES, key = KeyType.USER)
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReportResponse createReport() {
        return new ReportResponse(UUID.randomUUID().toString(), "QUEUED", instanceId);
    }

    public record PingResponse(String message, String instance, Instant timestamp) {
    }

    public record SearchResponse(String query, List<String> results, String instance) {
    }

    public record ReportResponse(String reportId, String status, String instance) {
    }
}
