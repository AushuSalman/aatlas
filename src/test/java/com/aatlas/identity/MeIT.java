package com.aatlas.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** {@code GET /api/v1/me}: user, company, persona and data source in one round trip. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeIT extends PostgresIntegrationTest {

    private static final String CLIENT_IP = "203.0.113.12";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String signUpAndGetToken(String role) throws Exception {
        String email = "casey-" + UUID.randomUUID() + "@kestrelsupply.com";
        String body = """
                {"fullName":"Casey Nolan","email":"%s","password":"Zephyr!42Bridge",
                 "company":"Kestrel Supply Co.","country":"UK","role":"%s"}
                """.formatted(email, role);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        return response.get("accessToken").asText();
    }

    @Test
    @DisplayName("returns the user, the tenant, the persona and a null data source for a fresh tenant")
    void returnsTheFullShape() throws Exception {
        String token = signUpAndGetToken("purchase-manager");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").exists())
                .andExpect(jsonPath("$.user.role").value("purchase-manager"))
                .andExpect(jsonPath("$.user.title").value("Purchase manager"))
                .andExpect(jsonPath("$.user.initials").value("CN"))
                .andExpect(jsonPath("$.company").value("Kestrel Supply Co."))
                .andExpect(jsonPath("$.country").value("UK"))
                .andExpect(jsonPath("$.currency").value("GBP"))
                .andExpect(jsonPath("$.persona.key").value("purchase-manager"))
                .andExpect(jsonPath("$.persona.side").value("buy"))
                .andExpect(jsonPath("$.persona.level").value("manager"))
                .andExpect(jsonPath("$.persona.bulk").value(false))
                .andExpect(jsonPath("$.persona.guardrails").value(false))
                .andExpect(jsonPath("$.persona.approveLimit").value(50000))
                .andExpect(jsonPath("$.persona.approver").value("Head of purchasing"))
                .andExpect(jsonPath("$.persona.modules").isArray())
                // No data_sources table exists in this worktree: NoDataSourceYet answers empty,
                // and the non-null Jackson inclusion setting drops the null key entirely.
                .andExpect(jsonPath("$.dataSource").doesNotExist());
    }

    @Test
    @DisplayName("a seat with no approval ceiling omits approveLimit rather than sending null")
    void unlimitedApproverOmitsTheLimit() throws Exception {
        String token = signUpAndGetToken("purchase-head");

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.persona.approveLimit").doesNotExist())
                .andExpect(jsonPath("$.persona.approver").doesNotExist());
    }

    @Test
    @DisplayName("no token, no answer")
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }
}
