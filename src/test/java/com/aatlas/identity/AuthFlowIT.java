package com.aatlas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Sign-in, refresh rotation and sign-out: the life of a session after signup.
 *
 * <p>Every request here carries its own {@code X-Forwarded-For}, distinct from every other
 * IT class's, so this class's logins and resets cannot run into {@code AuthRateLimiter}'s
 * per-client windows when the Spring test context (and so the limiter's in-process counters)
 * is reused across test classes in the same JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIT extends PostgresIntegrationTest {

    private static final String CLIENT_IP = "203.0.113.11";
    private static final String PASSWORD = "Zephyr!42Bridge";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AatlasClock clock;

    private static String uniqueEmail() {
        return "morgan-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private JsonNode signUp(String email, String role) throws Exception {
        String body = """
                {"fullName":"Morgan Reyes","email":"%s","password":"%s",
                 "company":"Halden Metals","country":"US","role":"%s"}
                """.formatted(email, PASSWORD, role);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("signs in and stamps last_login_at")
    void loginSucceeds() throws Exception {
        String email = uniqueEmail();
        signUp(email, "seller");

        // Against AatlasClock, not the wall clock: the test profile freezes "now" at
        // 2026-09-01, and the application stamps this column from that same frozen clock.
        Instant before = clock.now();
        mvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.session.user.email").value(email))
                .andExpect(jsonPath("$.session.isNewAccount").value(false));

        Instant lastLoginAt = jdbc.queryForObject(
                "select last_login_at from users where email_normalised = ?", Instant.class,
                email.toLowerCase());
        assertThat(lastLoginAt).isAfterOrEqualTo(before.minusSeconds(5));
    }

    @Test
    @DisplayName("a wrong password and an unknown email are the same 401")
    void wrongPasswordAndUnknownEmailAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        signUp(email, "buyer");

        MvcResult wrongPassword = mvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"NotTheRightOne1"}""".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andReturn();

        MvcResult unknownEmail = mvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody-%s@kestrelsupply.com","password":"NotTheRightOne1"}"""
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_credentials"))
                .andReturn();

        String wrongDetail = json.readTree(wrongPassword.getResponse().getContentAsString()).get("detail").asText();
        String unknownDetail = json.readTree(unknownEmail.getResponse().getContentAsString()).get("detail").asText();
        assertThat(wrongDetail).isEqualTo(unknownDetail);
    }

    @Test
    @DisplayName("refresh rotates the token; the old one becomes unusable")
    void refreshRotates() throws Exception {
        String email = uniqueEmail();
        JsonNode signup = signUp(email, "finance");
        String firstRefresh = signup.get("refreshToken").asText();

        MvcResult result = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(firstRefresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        String secondRefresh = json.readTree(result.getResponse().getContentAsString()).get("refreshToken").asText();
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);

        // The rotated-away token cannot be exchanged again.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(firstRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reusing a spent refresh token revokes the whole family")
    void reuseRevokesTheFamily() throws Exception {
        String email = uniqueEmail();
        JsonNode signup = signUp(email, "purchase-head");
        UUID userId = UUID.fromString(signup.at("/session/user/id").asText());
        String firstRefresh = signup.get("refreshToken").asText();

        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(firstRefresh)))
                .andExpect(status().isOk())
                .andReturn();
        String secondRefresh = json.readTree(rotated.getResponse().getContentAsString()).get("refreshToken").asText();

        // Presenting the spent token again is reuse: the whole family is revoked.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(firstRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("refresh_reused"));

        // The token that came out of the rotation is a casualty too, even though it was
        // never itself presented twice.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(secondRefresh)))
                .andExpect(status().isUnauthorized());

        Integer revoked = jdbc.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and revoked_at is not null",
                Integer.class, userId);
        assertThat(revoked).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("logout revokes the token and is idempotent")
    void logoutIsIdempotent() throws Exception {
        String email = uniqueEmail();
        JsonNode signup = signUp(email, "sales-rep");
        String refreshToken = signup.get("refreshToken").asText();

        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isNoContent());

        // Signing out twice is not an error: sign-out is idempotent.
        mvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isNoContent());

        // And the revoked token can no longer be exchanged.
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}""".formatted(refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("an unknown refresh token is a plain 401, not a 500")
    void unknownRefreshTokenIs401() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"not-a-real-token-at-all"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_refresh_token"));
    }
}
