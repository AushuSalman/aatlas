package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A ceiling on how often one caller may hit the unauthenticated endpoints.
 *
 * <p>Every one of them costs something that cannot be cached or shed: signup and login
 * run BCrypt at cost 12 (about a tenth of a second of CPU), forgot-password writes a row
 * and sends a mail, reset runs BCrypt again. Left open they are a spam vector, a cheap way
 * to saturate the pool and, for login, an online guessing oracle.
 *
 * <p>A fixed window, in process, on purpose. The obvious objection is that it is per pod,
 * so four pods allow four times the limit - true, and it still turns an unbounded flood
 * into a bounded one, which is the whole job. A distributed counter would need a Redis
 * round trip on the hot path and would put a hard dependency on a service the local
 * profile does not run. When the edge enforces this properly, delete the class.
 *
 * <p>Counted per client address rather than per email: the email is attacker-controlled
 * and varying it is free. Login is additionally guarded per account by the lockout in
 * {@code UserAccount}, which is what stops a distributed guess at one password.
 */
@Component
class AuthRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

    /** Bounded so the limiter cannot itself become a memory leak under a distributed flood. */
    private static final int MAX_TRACKED_CLIENTS = 100_000;

    /**
     * One window per endpoint. Every attempt counts, successful or not, which is what
     * protects the CPU: a request rejected for a weak password has already cost the same
     * work as one accepted.
     *
     * <p>The signup limit is ten rather than the two or three an account creation alone
     * would justify: a person choosing a password can easily be told "too short", then
     * "too common", then "do not use your email", and being locked out for an hour by a
     * form that kept moving the goalposts is its own kind of failure. Login allows twenty
     * in fifteen minutes - generous for a person mistyping, useless for a script.
     */
    enum Action {
        SIGNUP(10, Duration.ofHours(1), "too_many_signups",
                "Too many accounts created from this connection. Try again later."),
        LOGIN(20, Duration.ofMinutes(15), "too_many_attempts",
                "Too many sign-in attempts from this connection. Try again in a few minutes."),
        PASSWORD_FORGOT(5, Duration.ofHours(1), "too_many_attempts",
                "Too many reset requests from this connection. Try again later."),
        PASSWORD_RESET(10, Duration.ofHours(1), "too_many_attempts",
                "Too many reset attempts from this connection. Try again later."),
        /** Start and resend alike: each one is a real mail sent. */
        EMAIL_VERIFY_SEND(10, Duration.ofHours(1), "rate_limited",
                "Too many codes requested from this connection. Try again later."),
        /** Wide enough for typos; each challenge has its own five-attempt limit underneath. */
        EMAIL_VERIFY_CONFIRM(30, Duration.ofMinutes(15), "rate_limited",
                "Too many code attempts from this connection. Try again in a few minutes."),
        /** Exchange and sign-in alike; each exchange is a round trip to Google or Apple. */
        SSO(30, Duration.ofMinutes(15), "rate_limited",
                "Too many sign-in attempts from this connection. Try again in a few minutes."),
        /** Opening and accepting an invitation link. */
        INVITATION(30, Duration.ofHours(1), "rate_limited",
                "Too many attempts from this connection. Try again later.");

        final int maxPerWindow;
        final Duration window;
        final String code;
        final String message;

        Action(int maxPerWindow, Duration window, String code, String message) {
            this.maxPerWindow = maxPerWindow;
            this.window = window;
            this.code = code;
            this.message = message;
        }
    }

    private final Map<Action, Cache<String, AtomicInteger>> attempts = new EnumMap<>(Action.class);
    private final AatlasClock clock;

    AuthRateLimiter(AatlasClock clock) {
        this.clock = clock;
        for (Action action : Action.values()) {
            attempts.put(action, Caffeine.newBuilder()
                    .maximumSize(MAX_TRACKED_CLIENTS)
                    .expireAfterWrite(action.window)
                    .build());
        }
    }

    /**
     * Records an attempt from {@code clientIp} and fails once the window is spent.
     *
     * <p>Called before the password is hashed, so a rejected caller costs a map lookup
     * rather than BCrypt.
     *
     * @throws ApiException 429, carrying {@code retryAfterSeconds} for the client to honour
     */
    void checkAndRecord(Action action, String clientIp) {
        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        int used = attempts.get(action).get(key, unused -> new AtomicInteger()).incrementAndGet();
        if (used > action.maxPerWindow) {
            Instant now = clock.now();
            log.warn("{} rate limit exceeded for client {} ({} attempts in the window)", action, key, used);
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    action.code,
                    action.message,
                    Map.of("retryAfterSeconds", action.window.toSeconds(), "at", now.toString()));
        }
    }
}
