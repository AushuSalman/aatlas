package com.aatlas.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Bulk sell and bulk buy end to end, against a real PostgreSQL: signup, plan, apply, both
 * sides. See {@code aatlas.clock.fixed=false}'s note on {@code SuppliersIT} for why this
 * override is needed whenever a test authenticates more than one request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aatlas.clock.fixed=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String token;
    private String financeToken;

    private String signUp(String role) throws Exception {
        String body = """
                {
                  "fullName": "Alex Moreno",
                  "email": "bulk-%s@kestrelsupply.com",
                  "password": "Zephyr!42Bridge",
                  "company": "Kestrel Supply Co.",
                  "country": "US",
                  "role": "%s"
                }
                """.formatted(UUID.randomUUID(), role);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @BeforeAll
    void setUp() throws Exception {
        token = signUp("both");
        // finance has bulk=false in seed/personas.json: cannot apply a bulk strategy.
        financeToken = signUp("finance");
    }

    @Test
    @DisplayName("sell/bulk: plan three strategies, then apply one")
    void sellBulkPlanThenApply() throws Exception {
        MvcResult planResult = mvc.perform(get("/api/v1/sell/bulk/plan")
                        .param("store", "100959")
                        .param("items", "HRD304148,HRD118902,HRD450871,HRD661204,HRD512066,HRD874019,HRD248813")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value("100959"))
                .andExpect(jsonPath("$.lines.length()").value(7))
                .andExpect(jsonPath("$.strategies.length()").value(3))
                .andExpect(jsonPath("$.recommendedKey").isNotEmpty())
                .andReturn();
        JsonNode plan = json.readTree(planResult.getResponse().getContentAsString());
        String recommendedKey = plan.get("recommendedKey").asText();

        mvc.perform(post("/api/v1/sell/bulk/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "100959", "items": ["HRD304148", "HRD118902"], "strategyKey": "%s"}
                                """.formatted(recommendedKey)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionId").isNotEmpty())
                .andExpect(jsonPath("$.appliedStrategy").value(recommendedKey));
    }

    @Test
    @DisplayName("sell/bulk/apply refuses a seat without Persona.bulk")
    void sellBulkApplyRequiresBulkSeat() throws Exception {
        mvc.perform(post("/api/v1/sell/bulk/apply")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "100959", "items": ["HRD304148"], "strategyKey": "balanced"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_allowed"));
    }

    @Test
    @DisplayName("sell/bulk/apply rejects a strategy key not in the plan")
    void sellBulkApplyRejectsUnknownStrategy() throws Exception {
        mvc.perform(post("/api/v1/sell/bulk/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "100959", "items": ["HRD304148"], "strategyKey": "not-a-strategy"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("buy/bulk: plan five strategies, then apply one")
    void buyBulkPlanThenApply() throws Exception {
        MvcResult planResult = mvc.perform(get("/api/v1/buy/bulk/plan")
                        .param("region", "south")
                        .param("items", "HRD304148,HRD772310,HRD450871,HRD661204,HRD335590,HRD107744,HRD248813")
                        .param("horizon", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionKey").value("south"))
                .andExpect(jsonPath("$.lines.length()").value(7))
                .andExpect(jsonPath("$.strategies.length()").value(5))
                .andReturn();
        JsonNode plan = json.readTree(planResult.getResponse().getContentAsString());
        String recommendedKey = plan.get("recommendedKey").asText();
        assertThat(recommendedKey).isIn("lowest-cost", "balanced");

        mvc.perform(post("/api/v1/buy/bulk/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regionKey": "south", "items": ["HRD304148", "HRD772310"], "strategyKey": "split", "horizon": 1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionId").isNotEmpty())
                .andExpect(jsonPath("$.appliedStrategy").value("split"))
                .andExpect(jsonPath("$.plan.strategies.length()").value(5));
    }
}
