package com.aatlas.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code decisions} end to end against a real PostgreSQL: signup, connect the sample data
 * source (which starts with no deals - history is what the tenant does, never a fixture),
 * apply two recommendations, then every endpoint - history, impact, deals, decisions, and
 * recording a sale and a purchase through {@link DecisionRecorder}'s HTTP surface.
 *
 * <p>One tenant for the class, in order: the deal count is asserted before the applies and
 * pinned at two after them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-decisions-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DecisionsHistoryIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private String token;
    private UUID tenantId;

    @BeforeAll
    void connectSample() throws Exception {
        token = SampleTenant.signUpAndConnect(mvc, json, "both");
        tenantId = SampleTenant.tenantIdOf(json, token);
    }

    @Test
    @Order(1)
    @DisplayName("connecting the sample dataset starts with no deals")
    void sampleDataStartsWithNoDeals() {
        Integer total = jdbc.queryForObject("select count(*) from deal where tenant_id = ?", Integer.class, tenantId);
        assertThat(total).isZero();

        // History is a 404 until the first decision, not an empty page.
        try {
            mvc.perform(get("/api/v1/history").header("Authorization", "Bearer " + token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("no_history"));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    @Order(2)
    @DisplayName("applying two recommendations records two deals")
    void applyTwoRecommendations() throws Exception {
        apply("HRD118902", "100959");
        apply("HRD772310", "100117");

        Integer total = jdbc.queryForObject("select count(*) from deal where tenant_id = ?", Integer.class, tenantId);
        assertThat(total).isEqualTo(2);
    }

    private void apply(String item, String store) throws Exception {
        mvc.perform(post("/api/v1/sell/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + item + "\",\"store\":\"" + store + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.id").isNotEmpty());
    }

    @Test
    @Order(3)
    @DisplayName("GET /history returns the two rows and the summary adds up")
    void historyRowsAndSummary() throws Exception {
        mvc.perform(get("/api/v1/history").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2));

        mvc.perform(get("/api/v1/history/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions").value(2))
                .andExpect(jsonPath("$.gained").isNumber())
                .andExpect(jsonPath("$.lost").isNumber())
                .andExpect(jsonPath("$.net").isNumber());
    }

    @Test
    @Order(4)
    @DisplayName("GET /impact carries sell and buy summaries with the two applied deals")
    void impactCarriesBothSides() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/impact").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sell.side").value("sell"))
                .andExpect(jsonPath("$.buy.side").value("buy"))
                .andExpect(jsonPath("$.deals.length()").value(2))
                .andExpect(jsonPath("$.recorded.length()").value(2))
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("sell").get("deals").asInt()).isEqualTo(2);
    }

    @Test
    @Order(5)
    @DisplayName("GET /deals is keyset paged")
    void dealsPaged() throws Exception {
        MvcResult first = mvc.perform(get("/api/v1/deals?limit=1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = json.readTree(first.getResponse().getContentAsString()).get("nextCursor").asText();

        mvc.perform(get("/api/v1/deals?limit=1&cursor=" + cursor).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @Order(6)
    @DisplayName("recording a decision, a sale and a purchase - list, get and clear")
    void recordAndReadDecisions() throws Exception {
        MvcResult recorded = mvc.perform(post("/api/v1/decisions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "kind": "sell",
                                  "title": "Price applied for HRD118902 at Dallas — TX",
                                  "itemNumber": "HRD118902",
                                  "scope": "Dallas — TX",
                                  "recommended": 27.40,
                                  "applied": 27.40,
                                  "expectedImpact": 1250.00,
                                  "impactLabel": "over 12 months",
                                  "detail": "Optimal tier applied",
                                  "count": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("sell"))
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn();
        String decisionId = json.readTree(recorded.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/api/v1/decisions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(decisionId));

        mvc.perform(get("/api/v1/decisions/" + decisionId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.id").value(decisionId))
                .andExpect(jsonPath("$.deals").isArray());

        // This user's own decisions: the one above plus the two applies.
        mvc.perform(delete("/api/v1/decisions/mine").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));

        mvc.perform(get("/api/v1/decisions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
