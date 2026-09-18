package com.aatlas.identity.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Real mail, over Resend's REST API (<a href="https://resend.com/docs">resend.com/docs</a>)
 * instead of SMTP.
 *
 * <p>Render's web services block outbound SMTP entirely - every port, every host, not a
 * Gmail-specific block. Resend also offers an SMTP relay, which would hit the exact same
 * wall; this client talks to {@code https://api.resend.com} over plain HTTPS instead, which
 * nothing blocks. Content is shared with {@link SmtpMailer} through {@link MailTemplates} -
 * this class is only the transport, chosen with {@code MAIL_TRANSPORT=resend}.
 */
@Component
@ConditionalOnProperty(name = "aatlas.mail.transport", havingValue = "resend")
class ResendMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(ResendMailer.class);

    private final RestClient http;
    private final String apiKey;
    private final String from;
    private final String appUrl;

    ResendMailer(
            @Value("${aatlas.mail.resend.api-key}") String apiKey,
            @Value("${aatlas.mail.resend.from:onboarding@resend.dev}") String from,
            @Value("${aatlas.mail.from-name:Aatlas}") String fromName,
            @Value("${aatlas.mail.app-url:http://localhost:3000}") String appUrl) {
        this.apiKey = apiKey;
        this.from = fromName + " <" + from + ">";
        this.appUrl = appUrl.replaceAll("/+$", "");
        this.http = RestClient.builder()
                .baseUrl("https://api.resend.com")
                .requestFactory(clientRequestFactory())
                .build();
    }

    private static ClientHttpRequestFactory clientRequestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
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
    public void sendInvitation(String email, String fullName, String inviterName, String company, String token, Instant expiresAt) {
        var m = MailTemplates.invitation(fullName, inviterName, company, appUrl + "/accept-invite?token=" + token);
        send(email, m.subject(), m.text(), m.html());
    }

    private void send(String to, String subject, String text, String html) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new MailSendException("Resend is not configured (aatlas.mail.resend.api-key is empty).");
        }
        try {
            http.post()
                    .uri("/emails")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SendRequest(from, List.of(to), subject, html, text))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException ex) {
            log.warn("Resend send to {} failed: {}", to, ex.getMessage());
            throw new MailSendException("Resend request failed", ex);
        }
    }

    record SendRequest(String from, List<String> to, String subject, String html, String text) {
    }
}
