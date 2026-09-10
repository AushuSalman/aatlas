package com.aatlas.identity.internal;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the mail to the log instead of sending it.
 *
 * <p>The only mailer there is until SMTP is wired, and the right one for local
 * development: the reset link ends up in the console, which is where a developer is
 * looking. It logs the token in the clear, which is the point of this class and would be
 * wrong in production - replace it with an SMTP implementation before there is one.
 */
@Component
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
}
