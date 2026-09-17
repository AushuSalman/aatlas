package com.aatlas.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.realdata.SampleOracle;
import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
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
 * {@code analytics} end to end: signup, connect the sample data source (whose purchase
 * history loads through the real import path - no more ~800-row hashed fixture, see V22 and
 * the foundation commit), then every Group N endpoint reduced from the real
 * {@code purchase_order} table. One tenant for the class; every test against it is a read.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-analytics-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsProcurementIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AatlasClock clock;

    private String token;
    private UUID tenantId;

    @BeforeAll
    void connectSample() throws Exception {
        token = SampleTenant.signUpAndConnect(mvc, json, "both");
        tenantId = SampleTenant.tenantIdOf(json, token);
    }

    @Test
    @DisplayName("connecting the sample dataset loads exactly the purchase history in the sample file")
    void sampleDataLoadsTheLedger() {
        SampleOracle oracle = SampleTenant.oracle(clock);
        Integer count = jdbc.queryForObject("select count(*) from purchase_order where tenant_id = ?", Integer.class, tenantId);
        assertThat(count).isEqualTo(oracle.purchaseRows());
    }

    @Test
    @DisplayName("GET /analytics/procurement returns the full dashboard payload")
    void fullPayload() throws Exception {
        mvc.perform(get("/api/v1/analytics/procurement?range=90d").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.key").value("90d"))
                .andExpect(jsonPath("$.spend.value").isNumber())
                .andExpect(jsonPath("$.suppliers").isArray())
                .andExpect(jsonPath("$.supplierMix").isArray())
                .andExpect(jsonPath("$.opportunities").isArray());
    }

    @Test
    @DisplayName("?range=24m spend equals the oracle's landed spend over the same 729-day window, over real rows")
    void spendMatchesOracle() throws Exception {
        SampleOracle oracle = SampleTenant.oracle(clock);
        // DateRanges.resolveRange("24m", today) is today-729d .. today (729 = 24 x 30.4).
        BigDecimal expected = oracle.spend(clock.today().minusDays(729), clock.today());

        MvcResult result = mvc.perform(get("/api/v1/analytics/procurement?range=24m")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        double actual = body.get("spend").get("value").asDouble();
        assertThat(actual).isCloseTo(expected.doubleValue(), org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("GET /analytics/procurement/timeline, /suppliers, /delivery, /opportunities all answer")
    void subViews() throws Exception {
        mvc.perform(get("/api/v1/analytics/procurement/timeline?range=12m").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mvc.perform(get("/api/v1/analytics/procurement/suppliers?range=12m").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].risk").isNotEmpty());

        mvc.perform(get("/api/v1/analytics/procurement/delivery?range=12m").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery").isArray())
                .andExpect(jsonPath("$.lateLines").isArray());

        mvc.perform(get("/api/v1/analytics/procurement/opportunities?range=12m").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /analytics/procurement/mix supports every dimension")
    void mixDimensions() throws Exception {
        for (String dim : new String[] {"supplier", "category", "branch", "region", "origin"}) {
            mvc.perform(get("/api/v1/analytics/procurement/mix?dimension=" + dim + "&range=12m")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    @Test
    @DisplayName("GET /analytics/procurement/ledger is searchable, status-filterable and keyset paged, and carries poRef")
    void ledgerSearch() throws Exception {
        MvcResult first = mvc.perform(get("/api/v1/analytics/procurement/ledger?limit=25")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(25))
                .andExpect(jsonPath("$.items[0].poRef").isNotEmpty())
                .andReturn();
        JsonNode page1 = json.readTree(first.getResponse().getContentAsString());
        String cursor = page1.get("nextCursor").asText();

        mvc.perform(get("/api/v1/analytics/procurement/ledger?limit=25&cursor=" + cursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(25));

        // The generator leaves the most recent orders unreceived, so at least one is open.
        mvc.perform(get("/api/v1/analytics/procurement/ledger?status=open&limit=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("open"));
    }

    @Test
    @DisplayName("GET /analytics/procurement/ledger with a high limit returns exactly the oracle's row count")
    void ledgerCountMatchesOracle() throws Exception {
        SampleOracle oracle = SampleTenant.oracle(clock);
        MvcResult result = mvc.perform(get("/api/v1/analytics/procurement/ledger?limit=" + (oracle.purchaseRows() + 1))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("items").size()).isEqualTo(oracle.purchaseRows());
        // Jackson NON_NULL drops nextCursor from the JSON entirely at the end of the ledger -
        // path() (never Java null, unlike get()) reports that as a MissingNode.
        JsonNode nextCursor = body.path("nextCursor");
        assertThat(nextCursor.isMissingNode() || nextCursor.isNull() || nextCursor.asText().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("GET /analytics/procurement/ranges lists the presets and the earliest order")
    void ranges() throws Exception {
        mvc.perform(get("/api/v1/analytics/procurement/ranges").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ranges.length()").value(7))
                .andExpect(jsonPath("$.earliestOrder").isNotEmpty());
    }

    @Test
    @DisplayName("a tenant with no purchase history gets 404 no_ledger, not an empty dashboard")
    void noLedgerFor404() throws Exception {
        String freshToken = SampleTenant.signUp(mvc, json, "both", "US");
        mvc.perform(get("/api/v1/analytics/procurement?range=30d").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_ledger"));
    }
}
