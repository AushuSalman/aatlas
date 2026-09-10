package com.aatlas.policy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * The four pricing guardrails: read by anyone, written only by a seat whose persona says
 * {@code guardrails: true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GuardrailsIT extends PostgresIntegrationTest {

    private static final String CLIENT_IP = "203.0.113.15";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String signUpAndGetToken(String role) throws Exception {
        String email = "sasha-" + UUID.randomUUID() + "@kestrelsupply.com";
        String body = """
                {"fullName":"Sasha Iyer","email":"%s","password":"Zephyr!42Bridge",
                 "company":"Kestrel Supply Co.","country":"US","role":"%s"}
                """.formatted(email, role);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .header("X-Forwarded-For", CLIENT_IP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @DisplayName("a fresh tenant reads the platform defaults from seed/guardrails.json")
    void freshTenantReadsDefaults() throws Exception {
        String token = signUpAndGetToken("seller");

        mvc.perform(get("/api/v1/guardrails").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minMarginPct").value(25))
                .andExpect(jsonPath("$.maxDiscountPct").value(15))
                .andExpect(jsonPath("$.maxSpeedPremiumPct").value(8))
                .andExpect(jsonPath("$.maxMarketDeviationPct").value(10))
                .andExpect(jsonPath("$.updatedAt").doesNotExist())
                .andExpect(jsonPath("$.updatedBy").doesNotExist());
    }

    @Test
    @DisplayName("a seat without guardrails=true is refused with not_allowed")
    void disallowedSeatCannotSave() throws Exception {
        // seller: bulk, not guardrails - the seat that can reprice but not set the policy.
        String token = signUpAndGetToken("seller");

        mvc.perform(put("/api/v1/guardrails")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minMarginPct":30,"maxDiscountPct":12,"maxSpeedPremiumPct":6,"maxMarketDeviationPct":9}"""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_allowed"));
    }

    @Test
    @DisplayName("a head of sales can save, and the save is recorded in history")
    void headCanSaveAndHistoryRecordsIt() throws Exception {
        String token = signUpAndGetToken("sales-head");

        mvc.perform(put("/api/v1/guardrails")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minMarginPct":30,"maxDiscountPct":12,"maxSpeedPremiumPct":6,"maxMarketDeviationPct":9}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minMarginPct").value(30))
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.updatedBy").exists());

        mvc.perform(get("/api/v1/guardrails").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minMarginPct").value(30));

        MvcResult history = mvc.perform(get("/api/v1/guardrails/history").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = json.readTree(history.getResponse().getContentAsString()).get("items");
        org.assertj.core.api.Assertions.assertThat(items).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(items.get(0).get("action").asText()).isEqualTo("set");
        org.assertj.core.api.Assertions.assertThat(items.get(0).get("guardrails").get("minMarginPct").asInt())
                .isEqualTo(30);
    }

    @Test
    @DisplayName("a value outside its range is a 400 naming the field")
    void outOfRangeValueIsRejected() throws Exception {
        String token = signUpAndGetToken("both");

        mvc.perform(put("/api/v1/guardrails")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minMarginPct":2,"maxDiscountPct":12,"maxSpeedPremiumPct":6,"maxMarketDeviationPct":9}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.fields.minMarginPct").exists());
    }

    @Test
    @DisplayName("reset returns to the platform defaults and is itself recorded")
    void resetReturnsToDefaults() throws Exception {
        String token = signUpAndGetToken("both");

        mvc.perform(put("/api/v1/guardrails")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"minMarginPct":40,"maxDiscountPct":20,"maxSpeedPremiumPct":10,"maxMarketDeviationPct":15}"""))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/guardrails/reset").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minMarginPct").value(25))
                .andExpect(jsonPath("$.maxDiscountPct").value(15));

        MvcResult history = mvc.perform(get("/api/v1/guardrails/history").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = json.readTree(history.getResponse().getContentAsString()).get("items");
        org.assertj.core.api.Assertions.assertThat(items).hasSize(2);
        // Newest first: the reset.
        org.assertj.core.api.Assertions.assertThat(items.get(0).get("action").asText()).isEqualTo("reset");
        org.assertj.core.api.Assertions.assertThat(items.get(1).get("action").asText()).isEqualTo("set");
    }
}
