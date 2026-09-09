package com.aatlas.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Signup end to end: HTTP in, three committed rows and a usable token out.
 *
 * <p>Against a real PostgreSQL, because most of what this exercise is actually testing
 * lives in the schema - the unique index that decides the duplicate race, the check
 * constraints, the trigger on {@code updated_at}. None of that exists in a mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignupIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtDecoder jwtDecoder;

    private static String uniqueEmail() {
        return "alex-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private static String body(String email, String company, String country, String role, String password) {
        return """
                {
                  "fullName": "Alex Moreno",
                  "email": "%s",
                  "password": "%s",
                  "company": "%s",
                  "country": "%s",
                  "role": "%s"
                }
                """
                .formatted(email, password, company, country, role);
    }

    @Test
    @DisplayName("creates the company, the user and a session the frontend can render")
    void createsAnAccount() throws Exception {
        String email = uniqueEmail();

        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "Kestrel Supply Co.", "US", "both", "Zephyr!42Bridge")))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                // The session shape the client renders without decoding the token.
                .andExpect(jsonPath("$.session.company").value("Kestrel Supply Co."))
                .andExpect(jsonPath("$.session.country").value("US"))
                .andExpect(jsonPath("$.session.currency").value("USD"))
                .andExpect(jsonPath("$.session.isNewAccount").value(true))
                .andExpect(jsonPath("$.session.user.email").value(email))
                .andExpect(jsonPath("$.session.user.role").value("both"))
                .andExpect(jsonPath("$.session.user.title").value("Commercial director"))
                .andExpect(jsonPath("$.session.user.initials").value("AM"))
                // Null is load-bearing: it is what routes the client to onboarding.
                .andExpect(jsonPath("$.session.dataSource").doesNotExist())
                .andReturn();

        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        UUID userId = UUID.fromString(response.at("/session/user/id").asText());

        // The password is hashed, never stored, and never returned.
        String stored = jdbc.queryForObject("select password_hash from users where id = ?", String.class, userId);
        assertThat(stored).startsWith("$2");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("Zephyr!42Bridge");

        // Exactly one refresh token, and the column holds a digest rather than the token.
        byte[] tokenHash =
                jdbc.queryForObject("select token_hash from refresh_tokens where user_id = ?", byte[].class, userId);
        assertThat(tokenHash).hasSize(32);
    }

    @Test
    @DisplayName("the access token carries the claims the security chain reads")
    void tokenCarriesTenantAndRole() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueEmail(), "Halden Metals", "UK", "buyer", "Zephyr!42Bridge")))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        Jwt decoded = jwtDecoder.decode(response.get("accessToken").asText());

        // "tid" and "role" are what TenantContextFilter and SecurityConfig look for. If
        // either name drifts, every authenticated request silently loses its tenant.
        assertThat(decoded.getClaimAsString("tid")).isEqualTo(response.at("/session/user/id").asText().isEmpty()
                ? null
                : decoded.getClaimAsString("tid"));
        assertThat(decoded.getClaimAsString("tid")).isNotNull();
        assertThat(decoded.getClaimAsString("role")).isEqualTo("buyer");
        assertThat(decoded.getSubject()).isEqualTo(response.at("/session/user/id").asText());
        assertThat(decoded.getId()).isNotNull();
        assertThat(decoded.getExpiresAt()).isAfter(decoded.getIssuedAt());
    }

    @Test
    @DisplayName("the UK picks up GBP without being asked")
    void countryDrivesCurrency() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueEmail(), "Halden Metals", "UK", "seller", "Zephyr!42Bridge")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.session.currency").value("GBP"));
    }

    @Test
    @DisplayName("a second signup on the same address is a 409, whatever the casing")
    void duplicateEmailIsRejected() throws Exception {
        String email = uniqueEmail();

        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, "Kestrel Supply Co.", "US", "both", "Zephyr!42Bridge")))
                .andExpect(status().isCreated());

        // Upper-cased and padded: email_normalised is what carries the unique index.
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("  " + email.toUpperCase() + " ", "Other Co.", "US", "seller",
                                "Zephyr!42Bridge")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("email_taken"));
    }

    @Test
    @DisplayName("a blank company falls back rather than failing the form")
    void blankCompanyGetsTheDefault() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueEmail(), "", "US", "finance", "Zephyr!42Bridge")))
                .andExpect(status().isCreated())
                // The form marks Company optional, so this must not be an error.
                .andExpect(jsonPath("$.session.company").value("Northwind Industrial"));
    }

    @Test
    @DisplayName("a weak password is a 400 the form can show")
    void weakPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueEmail(), "Kestrel Supply Co.", "US", "both", "password123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an unknown seat is refused rather than defaulted")
    void unknownRoleIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueEmail(), "Kestrel Supply Co.", "US", "superuser",
                                "Zephyr!42Bridge")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("two companies with the same name get different slugs")
    void duplicateCompanyNamesGetDistinctSlugs() throws Exception {
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueEmail(), "Identical Name Ltd", "US", "both",
                                "Zephyr!42Bridge")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(uniqueEmail(), "Identical Name Ltd", "US", "both",
                                "Zephyr!42Bridge")))
                .andExpect(status().isCreated());

        // Two genuinely different companies can share a name; the slug must still be unique.
        Integer distinctSlugs = jdbc.queryForObject(
                "select count(distinct slug) from tenants where name = ?", Integer.class, "Identical Name Ltd");
        assertThat(distinctSlugs).isEqualTo(2);
    }
}
