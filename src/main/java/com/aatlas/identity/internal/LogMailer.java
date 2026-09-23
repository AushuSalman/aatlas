package com.aatlas.identity.internal;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes the mail to the log instead of sending it.
 *
 * <p>The default, and the right one for local development: the reset link and the
 * verification code end up in the console, which is where a developer is looking. It logs
 * secrets in the clear, which is the point of this class and would be wrong in production -
 * set {@code MAIL_TRANSPORT=smtp} there, which swaps in {@link SmtpMailer}.
 */
@Component
@ConditionalOnProperty(name = "aatlas.mail.transport", havingValue = "log", matchIfMissing = true)
class LogMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LogMailer.class);

    @Override
    public void sendPasswordReset(String email, String fullName, String token, Instant expiresAt) {
        log.info("""
                [mail] To: {} ({})
                [mail] Subject: Reset your Aatlas password
                [mail] Use this token within the hour (expires {}):
                [mail]   POST /api/v1/auth/password/reset {{"token": "{}", "password": "<new password>"}}""",
                email, fullName, expiresAt, token);
    }

    @Override
    public void sendMagicLink(String email, String fullName, String token, Instant expiresAt) {
        log.info("""
                [mail] To: {} ({})
                [mail] Subject: Your Aatlas sign-in link
                [mail] Open within fifteen minutes (expires {}):
                [mail]   /auth/magic?token={}""",
                email, fullName, expiresAt, token);
    }

    @Override
    public void sendVerificationCode(String email, String fullName, String code, Instant expiresAt) {
        log.info("""
                [mail] To: {}
                [mail] Subject: {} is your Aatlas verification code
                [mail] Code {} (expires {})""",
                email, code, code, expiresAt);
    }

    @Override
    public void sendInvitation(String email, String fullName, String inviterName, String company, String token, Instant expiresAt) {
        log.info("""
                [mail] To: {} ({})
                [mail] Subject: {} invited you to {} on Aatlas
                [mail] Accept within 7 days (expires {}):
                [mail]   /accept-invite?token={}""",
                email, fullName, inviterName, company, expiresAt, token);
    }
}
