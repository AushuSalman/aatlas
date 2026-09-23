package com.aatlas.identity.internal;

import java.time.Instant;

/**
 * The mail this module sends.
 *
 * <p>An interface so the transport is a deployment choice: {@link LogMailer} for a laptop
 * and the integration tests, {@link SmtpMailer} when {@code MAIL_TRANSPORT=smtp}. Nothing
 * in the flows that send mail should know which.
 */
interface Mailer {

    /**
     * @param token the one-time secret; the mail carries it in a link, and this is the
     *     only place it is ever written in the clear
     */
    void sendPasswordReset(String email, String fullName, String token, Instant expiresAt);

    /** A one-time sign-in link; the mailbox is the proof of who is asking. */
    void sendMagicLink(String email, String fullName, String token, Instant expiresAt);

    /**
     * @param fullName may be null on a resend
     * @param code the six digits; as with the reset token, only the mail carries it in the clear
     */
    void sendVerificationCode(String email, String fullName, String code, Instant expiresAt);

    /**
     * @param token the one-time invitation secret; the mail carries it in the accept link
     * @param inviterName who added them, so the mail is recognisably from a colleague
     */
    void sendInvitation(String email, String fullName, String inviterName, String company, String token, Instant expiresAt);
}
