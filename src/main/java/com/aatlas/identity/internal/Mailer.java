package com.aatlas.identity.internal;

import java.time.Instant;

/**
 * The one mail this module sends today.
 *
 * <p>An interface so the transport is a deployment choice: {@link LogMailer} for a laptop
 * and the integration tests, SMTP through {@code spring-boot-starter-mail} when an
 * {@code SMTP_HOST} exists. Nothing in the reset flow should know which.
 */
interface Mailer {

    /**
     * @param token the one-time secret; the mail carries it in a link, and this is the
     *     only place it is ever written in the clear
     */
    void sendPasswordReset(String email, String fullName, String token, Instant expiresAt);
}
