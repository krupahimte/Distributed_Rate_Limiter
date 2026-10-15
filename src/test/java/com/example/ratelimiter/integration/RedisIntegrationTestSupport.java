package com.example.ratelimiter.integration;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for full-application tests: the real app on a random port, talking to a real Redis in Docker.
 * Skipped (not failed) when Docker is not available.
 *
 * <p>One Redis container is shared by every integration test class and started on first use.
 * It is deliberately not a JUnit {@code @Container}: that would restart it per test class on a
 * new port, while Spring keeps cached application contexts pointing at the old port.
 * Testcontainers removes the container when the JVM exits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
public abstract class RedisIntegrationTestSupport {

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected StringRedisTemplate redis;

    @BeforeEach
    void flushRedis() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    protected ResponseEntity<String> get(String path, String userId) {
        return exchange(HttpMethod.GET, path, "X-User-Id", userId);
    }

    protected ResponseEntity<String> post(String path, String userId) {
        return exchange(HttpMethod.POST, path, "X-User-Id", userId);
    }

    /** Simulates a client behind a proxy; trusted because the test client connects via loopback. */
    protected ResponseEntity<String> getFromIp(String path, String clientIp) {
        return exchange(HttpMethod.GET, path, "X-Forwarded-For", clientIp);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String header, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(header, value);
        return rest.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    /** Freezes Redis: the connection stays open but nothing answers, so commands time out. */
    protected static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    protected static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
}
