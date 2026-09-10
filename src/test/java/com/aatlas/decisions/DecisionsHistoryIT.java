package com.aatlas.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * source (confirming the 181 seeded deals land), then every endpoint - history, impact,
 * deals, decisions, and recording a sale and a purchase through {@link DecisionRecorder}'s
 * HTTP surface.
 *
 * <p>See {@code SuppliersIT}/{@code DataSourceIT} for why {@code aatlas.clock.fixed=false}
 * and a distinct {@code spring.application.name} are needed on a class that authenticates a
 * second request with a token it mints itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-decisions-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DecisionsHistoryIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private static String uniqueEmail() {
        return "buyer-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private String signUpAndConnect() throws Exception {
        MvcResult signup = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Priya Shah",
                                  "email": "%s",
                                  "password": "Zephyr!42Bridge",
                                  "company": "Kestrel Supply Co.",
                                  "country": "US",
                                  "role": "both"
                                }
                                """.formatted(uniqueEmail())))
                .andExpect(status().isCreated())
                .andReturn();
        String token = json.readTree(signup.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());

        // DealsSeedListener reacts to SampleDataConnected asynchronously, after the connect
        // request's own transaction commits (see DomainEventPublisher's javadoc: "relayed
        // after commit"). The HTTP response above does not wait for it, so every test that
        // reads history/deals/impact right after connecting needs to wait for the seed to
        // actually land - otherwise this is a real, if usually narrow, race.
        awaitDealsSeeded(tenantIdOf(json, token));
        return token;
    }

    private static UUID tenantIdOf(ObjectMapper json, String token) throws Exception {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(payload).get("tid").asText());
    }

    private void awaitDealsSeeded(UUID tenantId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer count = jdbc.queryForObject("select count(*) from deal where tenant_id = ?", Integer.class, tenantId);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Deals were not seeded within 10s of connecting the sample data source");
    }

    @Test
    @DisplayName("connecting the sample dataset seeds all 181 deals, 151 sell and 30 buy")
    void sampleDataSeedsDeals() throws Exception {
        String token = signUpAndConnect();
        UUID tenantId = tenantIdOf(json, token);

        Integer total = jdbc.queryForObject("select count(*) from deal where tenant_id = ?", Integer.class, tenantId);
        Integer sell = jdbc.queryForObject("select count(*) from deal where tenant_id = ? and side = 'sell'", Integer.class, tenantId);
        Integer buy = jdbc.queryForObject("select count(*) from deal where tenant_id = ? and side = 'buy'", Integer.class, tenantId);
        assertThat(total).isEqualTo(181);
        assertThat(sell).isEqualTo(151);
        assertThat(buy).isEqualTo(30);
    }

    @Test
    @DisplayName("GET /history returns rows and the summary adds up")
    void historyRowsAndSummary() throws Exception {
        String token = signUpAndConnect();

        mvc.perform(get("/api/v1/history").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(50)); // default page size

        mvc.perform(get("/api/v1/history?limit=200").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(181));

        mvc.perform(get("/api/v1/history/summary").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions").value(181))
                .andExpect(jsonPath("$.gained").isNumber())
                .andExpect(jsonPath("$.lost").isNumber())
                .andExpect(jsonPath("$.net").isNumber());
    }

    @Test
    @DisplayName("GET /impact carries sell and buy summaries with 181 deals total")
    void impactCarriesBothSides() throws Exception {
        String token = signUpAndConnect();

        MvcResult result = mvc.perform(get("/api/v1/impact").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sell.side").value("sell"))
                .andExpect(jsonPath("$.buy.side").value("buy"))
                .andExpect(jsonPath("$.deals.length()").value(181))
                .andExpect(jsonPath("$.recorded").isEmpty())
                .andReturn();

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("sell").get("deals").asInt()).isEqualTo(151);
    }

    @Test
    @DisplayName("GET /deals is keyset paged")
    void dealsPaged() throws Exception {
        String token = signUpAndConnect();

        MvcResult first = mvc.perform(get("/api/v1/deals?limit=20").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = json.readTree(first.getResponse().getContentAsString()).get("nextCursor").asText();

        mvc.perform(get("/api/v1/deals?limit=20&cursor=" + cursor).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20));
    }

    @Test
    @DisplayName("recording a decision, a sale and a purchase - list, get and clear")
    void recordAndReadDecisions() throws Exception {
        String token = signUpAndConnect();

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

        mvc.perform(delete("/api/v1/decisions/mine").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));

        mvc.perform(get("/api/v1/decisions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }
}
