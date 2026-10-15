package com.example.ratelimiter.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class CorsPropertiesTest {

    @EnableConfigurationProperties(CorsProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaultsToViteDevServerAndComposeFrontend() {
        runner.run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                .containsExactly("http://localhost:5173", "http://localhost:3000"));
    }

    @Test
    void bindsCommaSeparatedList() {
        runner.withPropertyValues("app.cors.allowed-origins=https://a.example,https://b.example")
                .run(context -> assertThat(context.getBean(CorsProperties.class).allowedOrigins())
                        .containsExactly("https://a.example", "https://b.example"));
    }

    @Test
    void rejectsEmptyList() {
        runner.withPropertyValues("app.cors.allowed-origins=")
                .run(context -> assertThat(context).hasFailed());
    }
}
