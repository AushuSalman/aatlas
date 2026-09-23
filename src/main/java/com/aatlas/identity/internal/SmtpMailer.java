package com.aatlas.identity.internal;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real mail, over SMTP, through {@code spring.mail.*}.
 *
 * <p>Any SMTP relay works on a network that allows outbound SMTP - Gmail with an app
 * password, Brevo, Amazon SES. It does not on Render's web services, which block outbound
 * SMTP entirely regardless of provider or port; {@link ResendMailer} exists for exactly
 * that deployment target, over HTTPS instead. Each message here goes as multipart text and
 * HTML: the text part is what spam filters and plain-text clients read, and the code is on
 * its own line in both so a phone can offer to copy it.
 */
@Component
@ConditionalOnProperty(name = "aatlas.mail.transport", havingValue = "smtp")
class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final String from;
    private final String fromName;
    private final String appUrl;

    SmtpMailer(
            JavaMailSender sender,
            @Value("${aatlas.mail.from}") String from,
            @Value("${aatlas.mail.from-name:Aatlas}") String fromName,
            @Value("${aatlas.mail.app-url:http://localhost:3000}") String appUrl) {
        this.sender = sender;
        this.from = from;
        this.fromName = fromName;
        this.appUrl = appUrl.replaceAll("/+$", "");
    }

    @Override
    public void sendVerificationCode(String email, String fullName, String code, Instant expiresAt) {
        var m = MailTemplates.verificationCode(fullName, code, EmailVerificationService.CODE_TTL);
        send(email, m.subject(), m.text(), m.html());
    }

    @Override
    public void sendPasswordReset(String email, String fullName, String token, Instant expiresAt) {
        var m = MailTemplates.passwordReset(fullName, appUrl + "/reset-password?token=" + token);
        send(email, m.subject(), m.text(), m.html());
    }

    @Override
    public void sendMagicLink(String email, String fullName, String token, Instant expiresAt) {
        var m = MailTemplates.magicLink(fullName, appUrl + "/auth/magic?token=" + token);
        send(email, m.subject(), m.text(), m.html());
    }

    @Override
    public void sendInvitation(String email, String fullName, String inviterName, String company, String token, Instant expiresAt) {
        var m = MailTemplates.invitation(fullName, inviterName, company, appUrl + "/accept-invite?token=" + token);
        send(email, m.subject(), m.text(), m.html());
    }

    private void send(String to, String subject, String text, String html) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from, fromName);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            sender.send(message);
        } catch (MessagingException | UnsupportedEncodingException ex) {
            throw new MailSendException("Could not build the message", ex);
        }
    }
}
