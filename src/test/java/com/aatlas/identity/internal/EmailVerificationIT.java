package com.aatlas.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Start, confirm, then signup - with verification required, as it is outside the tests.
 *
 * <p>Same package and same capturing-mailer trick as {@link PasswordResetIT}: the code only
 * ever exists in the mail.
 */
@SpringBootTest(properties = "aatlas.auth.require-email-verification=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CapturingMailer mailer;

    @TestConfiguration
    static class MailerTestConfig {
        @Bean
        @Primary
        CapturingMailer capturingMailer() {
            return new CapturingMailer();
        }
    }

    static class CapturingMailer implements Mailer {
        final AtomicReference<String> lastCode = new AtomicReference<>();

        @Override
        public void sendPasswordReset(String email, String fullName, String token, Instant expiresAt) {
        }

        @Override
        public void sendMagicLink(String email, String fullName, String token, Instant expiresAt) {
        }

        @Override
        public void sendVerificationCode(String email, String fullName, String code, Instant expiresAt) {
            lastCode.set(code);
        }

        @Override
        public void sendInvitation(String email, String fullName, String inviterName, String company, String token, Instant expiresAt) {
        }
    }

    /** Each test its own address and connection, so the per-IP limiter never couples them. */
    private static String ip() {
        return "198.51.100." + (1 + (int) (Math.random() * 250));
    }

    private static String email() {
        return "sam-" + UUID.randomUUID() + "@haldenmetals.com";
    }

    private String start(String email, String ip) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/email/verify/start")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"fullName\":\"Sam Okafor\"}".formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maskedEmail").value(org.hamcrest.Matchers.endsWith("@haldenmetals.com")))
                .andExpect(jsonPath("$.attemptsLeft").value(5))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String id, String code, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/email/verify/confirm")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"%s\",\"code\":\"%s\"}".formatted(id, code)));
    }

    private org.springframework.test.web.servlet.ResultActions signUp(String email, String verificationId, String ip) throws Exception {
        String idField = verificationId == null ? "" : ",\"verificationId\":\"%s\"".formatted(verificationId);
        return mvc.perform(post("/api/v1/auth/signup")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Sam Okafor","email":"%s","password":"Zephyr!42Bridge",
                         "company":"Halden Metals","country":"US","role":"seller"%s}
                        """.formatted(email, idField)));
    }

    @Test
    @DisplayName("a mailed code, confirmed, lets signup create a verified account")
    void happyPath() throws Exception {
        String ip = ip();
        String email = email();
        String id = start(email, ip);
        String code = mailer.lastCode.get();
        assertThat(code).matches("\\d{6}");

        confirm(id, code, ip).andExpect(status().isNoContent());
        signUp(email, id, ip).andExpect(status().isCreated());

        Boolean verified = jdbc.queryForObject(
                "SELECT email_verified FROM users WHERE email_normalised = ?", Boolean.class, email);
        assertThat(verified).isTrue();

        // Spent: the same verification cannot open a second account.
        signUp(email(), id, ip).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("email_not_verified"));
    }

    @Test
    @DisplayName("signup without a verified address is refused")
    void signupNeedsVerification() throws Exception {
        String ip = ip();
        String email = email();
        signUp(email, null, ip).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("email_not_verified"));

        String id = start(email, ip);
        signUp(email, id, ip).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("email_not_verified"));
    }

    @Test
    @DisplayName("wrong codes cost attempts, and the fifth locks the challenge")
    void wrongCodes() throws Exception {
        String ip = ip();
        String id = start(email(), ip);
        String wrong = mailer.lastCode.get().equals("000000") ? "111111" : "000000";

        confirm(id, wrong, ip).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_code"))
                .andExpect(jsonPath("$.attemptsLeft").value(4));
        for (int i = 0; i < 3; i++) {
            confirm(id, wrong, ip).andExpect(jsonPath("$.code").value("invalid_code"));
        }
        confirm(id, wrong, ip).andExpect(jsonPath("$.code").value("too_many_attempts"));
        confirm(id, mailer.lastCode.get(), ip).andExpect(jsonPath("$.code").value("too_many_attempts"));
    }

    @Test
    @DisplayName("resend is refused until the backoff has passed")
    void resendBackoff() throws Exception {
        String ip = ip();
        String id = start(email(), ip);
        mvc.perform(post("/api/v1/auth/email/verify/resend")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeId\":\"%s\"}".formatted(id)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    @DisplayName("an address that already has an account is not sent a code")
    void existingAccount() throws Exception {
        String ip = ip();
        String email = email();
        String id = start(email, ip);
        confirm(id, mailer.lastCode.get(), ip).andExpect(status().isNoContent());
        signUp(email, id, ip).andExpect(status().isCreated());

        mvc.perform(post("/api/v1/auth/email/verify/start")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\"}".formatted(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_taken"));
    }
}
