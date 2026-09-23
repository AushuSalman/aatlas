package com.aatlas.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Magic-link sign-in, end to end: ask for a link, open it, be signed in - and find the link
 * dead afterwards. In {@code identity.internal} for the same reason as {@link PasswordResetIT}:
 * the token only ever leaves the API through {@link Mailer}, which is package-private.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MagicLinkIT extends PostgresIntegrationTest {

    private static final String CLIENT_IP = "203.0.113.14";

    @Autowired
    MockMvc mvc;

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

    /** Records the last sign-in token handed to it, so the test can use it like the mail would. */
    static class CapturingMailer implements Mailer {
        final AtomicReference<String> lastToken = new AtomicReference<>();
        final AtomicReference<String> lastEmail = new AtomicReference<>();

        @Override
        public void sendMagicLink(String email, String fullName, String token, Instant expiresAt) {
            lastEmail.set(email);
            lastToken.set(token);
        }

        @Override
        public void sendPasswordReset(String email, String fullName, String token, Instant expiresAt) {
            // Not exercised here; see PasswordResetIT.
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

    private String signUp(String email) throws Exception {
        String body = """
                {"fullName":"Rowan Blake","email":"%s","password":"Zephyr!42Bridge",
                 "company":"Halden Metals","country":"US","role":"seller"}
                """.formatted(email);
        mvc.perform(post("/api/v1/auth/signup")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        return email;
    }

    private void requestLink(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/magic-link/start")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("start is 202 whether or not the address has an account, and only a registered one gets mail")
    void startIsAlways202() throws Exception {
        String email = signUp("rowan-" + UUID.randomUUID() + "@kestrelsupply.com");
        mailer.lastToken.set(null);

        requestLink("nobody-" + UUID.randomUUID() + "@kestrelsupply.com");
        assertThat(mailer.lastToken.get()).isNull();

        requestLink(email);
        assertThat(mailer.lastToken.get()).isNotBlank();
        assertThat(mailer.lastEmail.get()).isEqualTo(email);
    }

    @Test
    @DisplayName("opening the link signs in exactly once")
    void consumeSignsInOnce() throws Exception {
        String email = signUp("rowan-" + UUID.randomUUID() + "@kestrelsupply.com");
        requestLink(email);
        String token = mailer.lastToken.get();
        assertThat(token).isNotBlank();

        mvc.perform(post("/api/v1/auth/magic-link/consume")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}""".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.session.user.email").value(email));

        // Spent: the same link cannot sign anyone in again.
        mvc.perform(post("/api/v1/auth/magic-link/consume")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s"}""".formatted(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_magic_link"));
    }

    @Test
    @DisplayName("a made-up token is a plain 400, not a 500")
    void unknownTokenIs400() throws Exception {
        mvc.perform(post("/api/v1/auth/magic-link/consume")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"not-a-token-we-issued"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_magic_link"));
    }
}
