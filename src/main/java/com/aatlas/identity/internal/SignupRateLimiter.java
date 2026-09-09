package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A ceiling on how many accounts one caller may create.
 *
 * <p>Signup is unauthenticated, writes three rows and runs BCrypt at cost 12 - about a
 * tenth of a second of CPU that cannot be cached or shed. Left open it is both a spam
 * vector and a cheap way to saturate the pool, so it gets a limit of its own rather than
 * relying on whatever the ingress happens to enforce.
 *
 * <p>A fixed window, in process, on purpose. The obvious objection is that it is per pod,
 * so four pods allow four times the limit - true, and it still turns an unbounded flood
 * into a bounded one, which is the whole job. A distributed counter would need a Redis
 * round trip on the hot path and would put a hard dependency on a service the local
 * profile does not run. When the edge enforces this properly, delete the class.
 *
 * <p>Counted per client address rather than per email: the email is attacker-controlled
 * and varying it is free.
 */
@Component
class SignupRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SignupRateLimiter.class);

    /**
     * Every attempt counts, successful or not - which is what protects the CPU, since a
     * request rejected for a weak password has already cost the same work as one accepted.
     *
     * <p>That choice is why the limit is ten rather than the two or three an account
     * creation alone would justify: a person choosing a password can easily be told "too
     * short", then "too common", then "do not use your email", and being locked out for an
     * hour by a form that kept moving the goalposts is its own kind of failure. Ten leaves
     * room for that and still bounds a script to a rate no spam run cares for.
     */
    private static final int MAX_PER_WINDOW = 10;
    private static final Duration WINDOW = Duration.ofHours(1);

    /** Bounded so the limiter cannot itself become a memory leak under a distributed flood. */
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    private final Cache<String, AtomicInteger> attempts;
    private final AatlasClock clock;

    SignupRateLimiter(AatlasClock clock) {
        this.clock = clock;
        this.attempts = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_CLIENTS)
                .expireAfterWrite(WINDOW)
                .build();
    }

    /**
     * Records an attempt from {@code clientIp} and fails once the window is spent.
     *
     * <p>Called before the password is hashed, so a rejected caller costs a map lookup
     * rather than BCrypt.
     *
     * @throws ApiException 429, carrying {@code retryAfterSeconds} for the client to honour
     */
    void checkAndRecord(String clientIp) {
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        int used = attempts.get(key, unused -> new AtomicInteger()).incrementAndGet();
        if (used > MAX_PER_WINDOW) {
            Instant now = clock.now();
            log.warn("Signup rate limit exceeded for client {} ({} attempts in the window)", key, used);
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "too_many_signups",
                    "Too many accounts created from this connection. Try again later.",
                    Map.of("retryAfterSeconds", WINDOW.toSeconds(), "at", now.toString()));
        }
    }
}
