package com.example.ratelimiter.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the application.yml setting {@code server.forward-headers-strategy=native}:
 * the real client IP behind a trusted proxy (here: the loopback test client) is what
 * {@code getRemoteAddr()} returns, and that is what the key resolver uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ForwardedHeadersTest.RemoteAddrController.class)
class ForwardedHeadersTest {

    @RestController
    static class RemoteAddrController {
        @GetMapping("/test/remote-addr")
        String remoteAddr(HttpServletRequest request) {
            return request.getRemoteAddr();
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void withoutProxyHeaderUsesSocketAddress() {
        assertThat(rest.getForObject("/test/remote-addr", String.class)).isEqualTo("127.0.0.1");
    }

    @Test
    void trustsForwardedForFromInternalProxy() {
        assertThat(remoteAddrWithForwardedFor("203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    void skipsTrustedProxiesInChainToFindClient() {
        // client -> 10.0.0.5 (internal proxy) -> loopback (internal proxy) -> app
        assertThat(remoteAddrWithForwardedFor("203.0.113.7, 10.0.0.5")).isEqualTo("203.0.113.7");
    }

    @Test
    void stopsAtFirstUntrustedHopSoClientsCannotSpoofEarlierEntries() {
        // 198.51.100.1 is a public address, so anything to its left was written by the client
        assertThat(remoteAddrWithForwardedFor("1.2.3.4, 198.51.100.1")).isEqualTo("198.51.100.1");
    }

    private String remoteAddrWithForwardedFor(String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", value);
        return rest.exchange("/test/remote-addr", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                .getBody();
    }
}
