package com.aatlas.identity.internal;

import java.time.Duration;
import org.springframework.web.util.HtmlUtils;

/**
 * The subject, plain-text and HTML for each mail this module sends - once, shared by every
 * {@link Mailer}. A transport ({@link SmtpMailer}, {@link ResendMailer}) only has to turn a
 * {@link Message} into a real send; it never composes copy.
 */
final class MailTemplates {

    private MailTemplates() {
    }

    record Message(String subject, String text, String html) {
    }

    static Message verificationCode(String fullName, String code, Duration ttl) {
        String greeting = fullName == null || fullName.isBlank() ? "Hi," : "Hi " + fullName.strip().split("\\s+")[0] + ",";
        long minutes = ttl.toMinutes();
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
        return new Message(code + " is your Aatlas verification code", text, html);
    }

    static Message passwordReset(String fullName, String link) {
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
        return new Message("Reset your Aatlas password", text, html);
    }

    static Message invitation(String fullName, String inviterName, String company, String link) {
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
        return new Message(inviterName + " invited you to " + company + " on Aatlas", text, html);
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
