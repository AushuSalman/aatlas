package com.aatlas.policy;

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

/**
 * {@code GET /api/v1/roles}: the eight personas seeded by V5 from {@code seed/personas.json},
 * as a fresh tenant with no overrides sees them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RolesIT extends PostgresIntegrationTest {

    private static final String CLIENT_IP = "203.0.113.14";

    /** The frontend's PERSONAS order, copied by hand - the point of the test. */
    private static final String[] EXPECTED_ORDER = {
        "sales-rep", "seller", "sales-head", "purchase-manager", "buyer", "purchase-head", "finance", "both"
    };

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String signUpAndGetToken() throws Exception {
        String email = "drew-" + UUID.randomUUID() + "@kestrelsupply.com";
        String body = """
                {"fullName":"Drew Ashford","email":"%s","password":"Zephyr!42Bridge",
                 "company":"Kestrel Supply Co.","country":"US","role":"both"}
                """.formatted(email);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/roles")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("returns all eight personas, in the seat picker's order")
    void returnsEightPersonasInOrder() throws Exception {
        String token = signUpAndGetToken();

        MvcResult result = mvc.perform(get("/api/v1/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andReturn();

        JsonNode roles = json.readTree(result.getResponse().getContentAsString());
        for (int i = 0; i < EXPECTED_ORDER.length; i++) {
            org.assertj.core.api.Assertions.assertThat(roles.get(i).get("key").asText())
                    .as("role at index " + i)
                    .isEqualTo(EXPECTED_ORDER[i]);
        }
    }

    @Test
    @DisplayName("approval limits and guardrail flags are pinned to the seed")
    void pinsApprovalLimitsAndGuardrailFlags() throws Exception {
        String token = signUpAndGetToken();

        mvc.perform(get("/api/v1/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // purchase-manager: $50k, approver named, no bulk, no guardrails.
                .andExpect(jsonPath("$[3].key").value("purchase-manager"))
                .andExpect(jsonPath("$[3].approveLimit").value(50000))
                .andExpect(jsonPath("$[3].approver").value("Head of purchasing"))
                .andExpect(jsonPath("$[3].bulk").value(false))
                .andExpect(jsonPath("$[3].guardrails").value(false))
                // buyer: $150k, bulk true.
                .andExpect(jsonPath("$[4].key").value("buyer"))
                .andExpect(jsonPath("$[4].approveLimit").value(150000))
                .andExpect(jsonPath("$[4].bulk").value(true))
                // purchase-head: no ceiling, and the field is absent rather than null.
                .andExpect(jsonPath("$[5].key").value("purchase-head"))
                .andExpect(jsonPath("$[5].approveLimit").doesNotExist())
                .andExpect(jsonPath("$[5].guardrails").value(true))
                // finance: reads only, sets the margin floor, does not price or buy.
                .andExpect(jsonPath("$[6].key").value("finance"))
                .andExpect(jsonPath("$[6].side").value("none"))
                .andExpect(jsonPath("$[6].guardrails").value(true))
                .andExpect(jsonPath("$[6].bulk").value(false))
                // the commercial director: every module, both sides.
                .andExpect(jsonPath("$[7].key").value("both"))
                .andExpect(jsonPath("$[7].side").value("both"))
                .andExpect(jsonPath("$[7].modules.length()").value(7));
    }
}
