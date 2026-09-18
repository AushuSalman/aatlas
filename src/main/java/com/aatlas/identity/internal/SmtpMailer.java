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
import org.springframework.web.util.HtmlUtils;

/**
 * Real mail, over SMTP, through {@code spring.mail.*}.
 *
 * <p>Any SMTP relay works - Gmail with an app password, Brevo, SendGrid, Amazon SES,
 * Resend. Each message goes as multipart text and HTML: the text part is what spam filters
 * and plain-text clients read, and the code is on its own line in both so a phone can
 * offer to copy it.
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
        String greeting = fullName == null || fullName.isBlank() ? "Hi," : "Hi " + fullName.strip().split("\\s+")[0] + ",";
        // From the policy rather than the clock: a mail rendered a second late should not say "9 minutes".
        long minutes = EmailVerificationService.CODE_TTL.toMinutes();
        String text = """
                %s

                Your Aatlas verification code is:

                %s

                It expires in %d minutes. If you did not try to create an Aatlas account, you can ignore this email.
                """.formatted(greeting, code, minutes);
        String html = layout("""
                <p style="margin:0 0 16px">%s</p>
                <p style="margin:0 0 16px">Enter this code to verify your email address:</p>
                <p style="margin:0 0 24px;font-size:32px;font-weight:700;letter-spacing:8px;font-family:ui-monospace,Menlo,Consolas,monospace">%s</p>
                <p style="margin:0 0 8px;color:#475467">It expires in %d minutes.</p>
                <p style="margin:0;color:#667085;font-size:13px">If you did not try to create an Aatlas account, you can ignore this email.</p>
                """.formatted(HtmlUtils.htmlEscape(greeting), code, minutes));
        send(email, code + " is your Aatlas verification code", text, html);
    }

    @Override
    public void sendPasswordReset(String email, String fullName, String token, Instant expiresAt) {
        String link = appUrl + "/reset-password?token=" + token;
        String text = """
                Hi %s,

                Someone asked to reset the password for your Aatlas account. Open this link within the hour to choose a new one:

                %s

                If it was not you, ignore this email; your password has not changed.
                """.formatted(fullName, link);
        String html = layout("""
                <p style="margin:0 0 16px">Hi %s,</p>
                <p style="margin:0 0 24px">Someone asked to reset the password for your Aatlas account. The link works for one hour.</p>
                <p style="margin:0 0 24px"><a href="%s" style="display:inline-block;background:#465fff;color:#fff;text-decoration:none;padding:12px 20px;border-radius:8px;font-weight:600">Reset password</a></p>
                <p style="margin:0;color:#667085;font-size:13px">If it was not you, ignore this email; your password has not changed.</p>
                """.formatted(HtmlUtils.htmlEscape(fullName), HtmlUtils.htmlEscape(link)));
        send(email, "Reset your Aatlas password", text, html);
    }

    @Override
    public void sendInvitation(String email, String fullName, String inviterName, String company, String token, Instant expiresAt) {
        String link = appUrl + "/accept-invite?token=" + token;
        String first = fullName == null || fullName.isBlank() ? "there" : fullName.strip().split("\\s+")[0];
        String text = """
                Hi %s,

                %s has invited you to join %s on Aatlas.

                Open this link to set your password and sign in:

                %s

                The invitation expires in 7 days. If you were not expecting it, you can ignore this email.
                """.formatted(first, inviterName, company, link);
        String html = layout("""
                <p style="margin:0 0 16px">Hi %s,</p>
                <p style="margin:0 0 24px"><strong>%s</strong> has invited you to join <strong>%s</strong> on Aatlas.</p>
                <p style="margin:0 0 24px"><a href="%s" style="display:inline-block;background:#465fff;color:#fff;text-decoration:none;padding:12px 20px;border-radius:8px;font-weight:600">Accept invitation</a></p>
                <p style="margin:0 0 8px;color:#475467">You will choose a password, then sign in with this email address.</p>
                <p style="margin:0;color:#667085;font-size:13px">The invitation expires in 7 days. If you were not expecting it, you can ignore this email.</p>
                """.formatted(HtmlUtils.htmlEscape(first), HtmlUtils.htmlEscape(inviterName),
                HtmlUtils.htmlEscape(company), HtmlUtils.htmlEscape(link)));
        send(email, inviterName + " invited you to " + company + " on Aatlas", text, html);
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

    private static String layout(String body) {
        return """
                <!doctype html><html><body style="margin:0;background:#f2f4f7;padding:32px 16px;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:#101828;font-size:15px;line-height:1.5">
                <div style="max-width:480px;margin:0 auto;background:#fff;border-radius:12px;padding:32px">
                <p style="margin:0 0 24px;font-weight:700;font-size:18px">Aatlas</p>
                %s
                </div></body></html>""".formatted(body);
    }
}
