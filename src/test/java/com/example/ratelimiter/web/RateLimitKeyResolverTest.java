package com.example.ratelimiter.web;

import com.example.ratelimiter.annotation.KeyType;
import com.example.ratelimiter.annotation.RateLimit;
import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.core.Algorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeyResolverTest {

    private final RateLimitKeyResolver resolver = new RateLimitKeyResolver(
            new RateLimiterProperties(true, Algorithm.TOKEN_BUCKET, true, "rl"));

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    static class Endpoints {
        @RateLimit(capacity = 1, refillRate = 1, key = KeyType.USER)
        void perUser() {
        }

        @RateLimit(capacity = 1, refillRate = 1, key = KeyType.IP)
        void perIp() {
        }

        @RateLimit(capacity = 1, refillRate = 1, key = KeyType.USER, name = "shared-search")
        void named() {
        }
    }

    @Test
    void userKeyUsesHeaderAndDefaultsNameToClassAndMethod() throws Exception {
        request.addHeader("X-User-Id", "alice-42");

        assertThat(resolve("perUser")).isEqualTo("rl:Endpoints.perUser:user:alice-42");
    }

    @Test
    void ipKeyUsesRemoteAddressAndIgnoresUserHeader() throws Exception {
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-User-Id", "alice");

        assertThat(resolve("perIp")).isEqualTo("rl:Endpoints.perIp:ip:203.0.113.7");
    }

    @Test
    void ipKeyIgnoresForwardedForHeaderItself() throws Exception {
        // Trusting X-Forwarded-For is Tomcat's job (RemoteIpValve), not the resolver's
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "1.2.3.4");

        assertThat(resolve("perIp")).isEqualTo("rl:Endpoints.perIp:ip:203.0.113.7");
    }

    @Test
    void explicitNameOverridesMethodName() throws Exception {
        request.addHeader("X-User-Id", "bob");

        assertThat(resolve("named")).isEqualTo("rl:shared-search:user:bob");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "a:b", "has space", "<script>",
            "this-user-id-is-way-too-long-0123456789012345678901234567890123456789"})
    void userKeyFallsBackToIpWhenHeaderMissingOrInvalid(String userId) throws Exception {
        request.setRemoteAddr("198.51.100.9");
        if (userId != null) {
            request.addHeader("X-User-Id", userId);
        }

        assertThat(resolve("perUser")).isEqualTo("rl:Endpoints.perUser:ip:198.51.100.9");
    }

    @Test
    void supportsIpv6Addresses() throws Exception {
        request.setRemoteAddr("2001:db8::1");

        assertThat(resolve("perIp")).isEqualTo("rl:Endpoints.perIp:ip:2001:db8::1");
    }

    private String resolve(String methodName) throws NoSuchMethodException {
        Method method = Endpoints.class.getDeclaredMethod(methodName);
        return resolver.resolve(request, method.getAnnotation(RateLimit.class), method);
    }
}
