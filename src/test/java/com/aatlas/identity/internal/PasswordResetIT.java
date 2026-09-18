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
 * Forgot and reset, end to end.
 *
 * <p>Lives in {@code identity.internal} because it needs to stand in for {@link Mailer},
 * which is package-private: the only way to capture the one-time token that {@link LogMailer}
 * would otherwise only write to the log is a test double registered in its own package.
 *
 * <p>{@code @Primary} on the test double overrides {@link LogMailer} for this test class's
 * Spring context; Spring caches contexts by configuration, so this runs in a context
 * separate from every IT that uses the real mailer, and cannot see tokens they issue.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetIT extends PostgresIntegrationTest {

    private static final String CLIENT_IP = "203.0.113.13";

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

    /** Records the last reset token handed to it, so the test can use it like the mail would. */
    static class CapturingMailer implements Mailer {
        final AtomicReference<String> lastToken = new AtomicReference<>();
        final AtomicReference<String> lastEmail = new AtomicReference<>();

        @Override
        public void sendPasswordReset(String email, String fullName, String token, Instant expiresAt) {
            lastEmail.set(email);
            lastToken.set(token);
        }

        @Override
        public void sendVerificationCode(String email, String fullName, String code, Instant expiresAt) {
            // Not exercised here; see EmailVerificationIT.
        }

        @Override
        public void sendInvitation(String email, String fullName, String inviterName, String company, String token, Instant expiresAt) {
            // Not exercised here.
        }
    }

    private String signUp(String email, String password) throws Exception {
        String body = """
                {"fullName":"Rowan Blake","email":"%s","password":"%s",
                 "company":"Halden Metals","country":"US","role":"seller"}
                """.formatted(email, password);
        mvc.perform(post("/api/v1/auth/signup")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return email;
    }

    @Test
    @DisplayName("forgot is 202 whether or not the address has an account")
    void forgotIsAlways202() throws Exception {
        String email = "rowan-" + UUID.randomUUID() + "@kestrelsupply.com";
        signUp(email, "Zephyr!42Bridge");

        mvc.perform(post("/api/v1/auth/password/forgot")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/v1/auth/password/forgot")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody-%s@kestrelsupply.com"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("a valid token sets the password and signs the account out everywhere")
    void resetChangesThePasswordAndSignsOutEverywhere() throws Exception {
        String email = "rowan-" + UUID.randomUUID() + "@kestrelsupply.com";
        signUp(email, "Zephyr!42Bridge");

        mvc.perform(post("/api/v1/auth/password/forgot")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andExpect(status().isAccepted());

        String token = mailer.lastToken.get();
        assertThat(token).isNotBlank();
        assertThat(mailer.lastEmail.get()).isEqualTo(email);

        mvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"NewZephyr!99Bridge"}""".formatted(token)))
                .andExpect(status().isNoContent());

        // The refresh token signup handed out is revoked: reset signs out everywhere. Checked
        // before the logins below, each of which mints a fresh live token of its own.
        Integer live = jdbc.queryForObject(
                "select count(*) from refresh_tokens rt join users u on u.id = rt.user_id "
                        + "where u.email_normalised = ? and rt.revoked_at is null and rt.used_at is null",
                Integer.class, email.toLowerCase());
        assertThat(live).isZero();

        // The new password works, the old one does not.
        mvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"NewZephyr!99Bridge"}""".formatted(email)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Zephyr!42Bridge"}""".formatted(email)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a spent reset token cannot be used twice")
    void resetTokenIsSingleUse() throws Exception {
        String email = "rowan-" + UUID.randomUUID() + "@kestrelsupply.com";
        signUp(email, "Zephyr!42Bridge");

        mvc.perform(post("/api/v1/auth/password/forgot")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andExpect(status().isAccepted());
        String token = mailer.lastToken.get();

        mvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"NewZephyr!99Bridge"}""".formatted(token)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","password":"AnotherOne!123"}""".formatted(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_reset_token"));
    }

    @Test
    @DisplayName("an unknown token is rejected the same way an expired one would be")
    void unknownTokenIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/password/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"not-a-real-token","password":"Zephyr!42Bridge"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_reset_token"));
    }
}
