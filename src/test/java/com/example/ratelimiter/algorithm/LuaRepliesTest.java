package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.core.RateLimitResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LuaRepliesTest {

    @Test
    void mapsAllowedAndDeniedReplies() {
        assertThat(LuaReplies.toResult(List.of(1L, 4L, 0L), 5)).isEqualTo(RateLimitResult.allowed(5, 4));
        assertThat(LuaReplies.toResult(List.of(0L, 0L, 900L), 5)).isEqualTo(RateLimitResult.denied(5, 900));
    }

    @Test
    void rejectsMissingReply() {
        assertThatIllegalStateException()
                .isThrownBy(() -> LuaReplies.toResult(null, 5))
                .withMessageContaining("Unexpected rate limit script reply");
    }

    @Test
    void rejectsReplyWithWrongNumberOfElements() {
        assertThatIllegalStateException()
                .isThrownBy(() -> LuaReplies.toResult(List.of(1L, 2L), 5))
                .withMessageContaining("[1, 2]");
    }

    @Test
    void rejectsNonIntegerElements() {
        assertThatIllegalStateException()
                .isThrownBy(() -> LuaReplies.toResult(Arrays.asList("1", 2L, 0L), 5))
                .withMessageContaining("Expected integer");
    }
}
