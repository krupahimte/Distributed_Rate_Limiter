package com.example.ratelimiter.annotation;

/**
 * What a rate limit is counted against.
 */
public enum KeyType {
    /** One bucket per caller, identified by the {@code X-User-Id} request header. */
    USER,
    /** One bucket per client IP address. */
    IP
}
