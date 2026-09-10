package com.aatlas.analytics;

import static org.assertj.core.api.Assertions.assertThat;
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
 * {@code analytics} end to end: signup, connect the sample data source (confirming ~800
 * purchase orders land), then every Group N endpoint.
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

        // ProcurementLedgerSeedListener reacts to SampleDataConnected asynchronously, after
        // the connect request's own transaction commits - the HTTP response does not wait
        // for ~800 rows to land, so every test that reads the ledger right after connecting
        // needs to wait for the seed to actually finish.
        awaitLedgerSeeded(tenantIdOf(json, token));
        return token;
    }

    private static UUID tenantIdOf(ObjectMapper json, String token) throws Exception {
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        return UUID.fromString(json.readTree(payload).get("tid").asText());
    }

    private void awaitLedgerSeeded(UUID tenantId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer count = jdbc.queryForObject("select count(*) from purchase_order where tenant_id = ?", Integer.class, tenantId);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The procurement ledger was not seeded within 10s of connecting the sample data source");
    }

    @Test
    @DisplayName("connecting the sample dataset writes the ~800-row procurement ledger")
    void sampleDataSeedsLedger() throws Exception {
        String token = signUpAndConnect();
        UUID tenantId = tenantIdOf(json, token);

        Integer count = jdbc.queryForObject("select count(*) from purchase_order where tenant_id = ?", Integer.class, tenantId);
        assertThat(count).isBetween(600, 1000);
    }

    @Test
    @DisplayName("GET /analytics/procurement returns the full dashboard payload")
    void fullPayload() throws Exception {
        String token = signUpAndConnect();

        mvc.perform(get("/api/v1/analytics/procurement?range=90d").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range.key").value("90d"))
                .andExpect(jsonPath("$.spend.value").isNumber())
                .andExpect(jsonPath("$.suppliers").isArray())
                .andExpect(jsonPath("$.supplierMix").isArray())
                .andExpect(jsonPath("$.opportunities").isArray());
    }

    @Test
    @DisplayName("GET /analytics/procurement/timeline, /suppliers, /delivery, /opportunities all answer")
    void subViews() throws Exception {
        String token = signUpAndConnect();

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
        String token = signUpAndConnect();
        for (String dim : new String[] {"supplier", "category", "branch", "region", "origin"}) {
            mvc.perform(get("/api/v1/analytics/procurement/mix?dimension=" + dim + "&range=12m")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }

    @Test
    @DisplayName("GET /analytics/procurement/ledger is searchable, status-filterable and keyset paged")
    void ledgerSearch() throws Exception {
        String token = signUpAndConnect();

        MvcResult first = mvc.perform(get("/api/v1/analytics/procurement/ledger?limit=25")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(25))
                .andReturn();
        JsonNode page1 = json.readTree(first.getResponse().getContentAsString());
        String cursor = page1.get("nextCursor").asText();

        mvc.perform(get("/api/v1/analytics/procurement/ledger?limit=25&cursor=" + cursor)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(25));

        mvc.perform(get("/api/v1/analytics/procurement/ledger?status=open&limit=10")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("open"));
    }

    @Test
    @DisplayName("GET /analytics/procurement/ranges lists the presets and the earliest order")
    void ranges() throws Exception {
        String token = signUpAndConnect();

        mvc.perform(get("/api/v1/analytics/procurement/ranges").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ranges.length()").value(7))
                .andExpect(jsonPath("$.earliestOrder").isNotEmpty());
    }
}
