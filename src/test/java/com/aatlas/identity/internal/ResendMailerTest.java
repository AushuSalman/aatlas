package com.aatlas.identity.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Sends one real message through the live Resend API. Gated on a real key being present,
 * exactly like {@code TavilyClientTest} - never runs in CI, only when a developer sets
 * {@code RESEND_API_KEY} and wants to prove the transport actually works end to end.
 */
@EnabledIfEnvironmentVariable(named = "RESEND_API_KEY", matches = ".+")
class ResendMailerTest {

    private final ResendMailer mailer = new ResendMailer(
            System.getenv("RESEND_API_KEY"),
            "onboarding@resend.dev",
            "Aatlas",
            "http://localhost:3000");

    @Test
    void sendsARealVerificationCode() {
        String to = System.getenv().getOrDefault("RESEND_TEST_TO", "shaiksalman0507@gmail.com");
        assertDoesNotThrow(() -> mailer.sendVerificationCode(to, "Test User", "123456", Instant.now().plusSeconds(600)));
    }
}
