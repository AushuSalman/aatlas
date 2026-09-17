package com.aatlas.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Window;
import com.aatlas.realdata.SampleOracle;
import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Overview, Insights-sell, Stores and Products, end to end against a real PostgreSQL: a
 * fresh tenant is gated until it connects a data source, then every endpoint reads back
 * real numbers computed from the seeded sample catalogue.
 *
 * <p>Figures asserted below are the same ones {@code GeoEngineGoldenTest} and
 * {@code DemographicsEngineGoldenTest} pin against the TypeScript's own recorded output,
 * so a passing golden test plus a passing read here is the same guarantee
 * {@code CatalogIT} gives catalog's data: the wiring from HTTP request to engine to
 * response is correct, not just the arithmetic in isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-insights-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightsIT extends PostgresIntegrationTest {

    private static final Pattern DAY_TOKEN = Pattern.compile("D-(\\d+)");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AatlasClock clock;

    private String token;

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private String signUp(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Dana Ellis",
                                  "email": "%s",
                                  "password": "Zephyr!42Bridge",
                                  "company": "Kestrel Supply Co.",
                                  "country": "US",
                                  "role": "both"
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @BeforeAll
    void connectedTenant() throws Exception {
        token = signUp(uniqueEmail("insights"));
        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
        // The sample history loads through the import path after connect; reading before it
        // lands would race the loader.
        SampleTenant.awaitReady(mvc, json, token);
    }

    @Test
    @DisplayName("a tenant with no data source gets 404 no_catalogue on every route")
    void noCatalogueBeforeConnecting() throws Exception {
        String freshToken = signUp(uniqueEmail("unconnected"));

        mvc.perform(get("/api/v1/overview").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
        mvc.perform(get("/api/v1/insights/regions").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
        mvc.perform(get("/api/v1/insights/stores").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
        mvc.perform(get("/api/v1/products/scores").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
    }

    @Test
    @DisplayName("GET /overview: six KPIs, the since-yesterday sentence, opportunities, risks and changes")
    void overviewCarriesEveryPiece() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpis.length()").value(6))
                .andExpect(jsonPath("$.kpis[0].key").value("revenue"))
                .andExpect(jsonPath("$.sinceYesterday.demandRegion.key").isNotEmpty())
                .andExpect(jsonPath("$.opportunities[0].impact").isNumber())
                .andExpect(jsonPath("$.decisions").isArray())
                .andExpect(jsonPath("$.regions.length()").value(4))
                .andExpect(jsonPath("$.stores.length()").value(9))
                .andReturn();
        JsonNode overview = json.readTree(result.getResponse().getContentAsString());
        assertThat(overview.get("decisions")).isEmpty();
    }

    @Test
    @DisplayName("GET /overview/changes, /overview/opportunities, /overview/risks")
    void overviewSubViews() throws Exception {
        mvc.perform(get("/api/v1/overview/changes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/overview/opportunities").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='raise')]").exists());
        mvc.perform(get("/api/v1/overview/risks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /insights/regions and /insights/regions/{key}: the South, with Dallas in it")
    void regionsAndOneRegion() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/insights/regions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn();
        // No constant CLIMATE map any more: on the sample tenant every one of the four real
        // regions has stores, so none is the "unassigned" bucket.
        JsonNode regions = json.readTree(result.getResponse().getContentAsString());
        for (JsonNode region : regions) {
            assertThat(region.get("key").asText()).isNotEqualTo("unassigned");
        }

        MvcResult southResult = mvc.perform(get("/api/v1/insights/regions/{key}", "south").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("south"))
                .andExpect(jsonPath("$.storeIds", org.hamcrest.Matchers.hasItem("100959")))
                .andReturn();
        JsonNode south = json.readTree(southResult.getResponse().getContentAsString());
        assertThat(south.get("headline").asText()).isIn(
                "Growing demand", "High margin", "Softening demand", "Thin margin", "Largest market", "Steady");
    }

    @Test
    @DisplayName("an unknown region key is a 404")
    void unknownRegionIs404() throws Exception {
        mvc.perform(get("/api/v1/insights/regions/{key}", "nowhere").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("GET /insights/stores and /insights/stores/{id}: Dallas #100959's own numbers")
    void storesAndOneStore() throws Exception {
        mvc.perform(get("/api/v1/insights/stores").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(9));

        mvc.perform(get("/api/v1/insights/stores/{id}", "100959").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value("100959"))
                .andExpect(jsonPath("$.label").value("Dallas #100959"))
                .andExpect(jsonPath("$.regionKey").value("south"))
                .andExpect(jsonPath("$.opportunities").isArray())
                .andExpect(jsonPath("$.products").isArray());
    }

    @Test
    @DisplayName("an unknown branch is a 404")
    void unknownStoreIs404() throws Exception {
        mvc.perform(get("/api/v1/insights/stores/{id}", "999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("GET /insights/demographics: the default filter, and one narrowed by region")
    void demographicsDefaultAndFiltered() throws Exception {
        mvc.perform(get("/api/v1/insights/demographics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeLabel").value("All regions"))
                .andExpect(jsonPath("$.segments").isArray())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.origins").isArray());

        mvc.perform(get("/api/v1/insights/demographics").header("Authorization", "Bearer " + token)
                        .param("region", "south"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.places[?(@.storeId=='100959')]").exists());
    }

    @Test
    @DisplayName("GET /insights/price-moves: the biggest expected 90-day moves in scope")
    void priceMoves() throws Exception {
        mvc.perform(get("/api/v1/insights/price-moves").header("Authorization", "Bearer " + token)
                        .param("region", "south"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /products/scores: every priceable item-branch pair, filterable by tier and region")
    void productScores() throws Exception {
        mvc.perform(get("/api/v1/products/scores").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemNumber").isNotEmpty())
                .andExpect(jsonPath("$[0].reasons").isArray());

        mvc.perform(get("/api/v1/products/scores").header("Authorization", "Bearer " + token)
                        .param("filter", "strong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tier!='strong')]").doesNotExist());
    }

    @Test
    @DisplayName("GET /products/{item}/scores: the flagship copper tube, at every branch")
    void oneProductsScores() throws Exception {
        mvc.perform(get("/api/v1/products/{item}/scores", "HRD118902").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.storeId=='100959')]").exists());
    }

    @Test
    @DisplayName("an unknown item number is a 404")
    void unknownProductIs404() throws Exception {
        mvc.perform(get("/api/v1/products/{item}/scores", "NOPE-000").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("kpis[revenue] matches the Hardin sample's real trailing-twelve-month revenue")
    void revenueKpiMatchesTheOracle() throws Exception {
        SampleOracle oracle = SampleOracle.load(clock.today());
        Window w12 = Window.trailingMonths(clock.today(), 12);
        BigDecimal expected = oracle.revenue(w12);

        MvcResult result = mvc.perform(get("/api/v1/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode overview = json.readTree(result.getResponse().getContentAsString());
        JsonNode revenueKpi = kpi(overview, "revenue");

        double tolerance = Math.max(1.00, expected.doubleValue() * 0.0005);
        assertThat(revenueKpi.get("value").asDouble()).isCloseTo(expected.doubleValue(), org.assertj.core.data.Offset.offset(tolerance));
        assertThat(revenueKpi.get("foot").asText()).isEqualTo("trailing twelve months");
    }

    @Test
    @DisplayName("kpis[adoption]: locked with no decisions, then real after one POST /sell/apply")
    void adoptionKpiLocksThenReportsARealDecision() throws Exception {
        String freshToken = signUp(uniqueEmail("adoption"));
        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + freshToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
        SampleTenant.awaitReady(mvc, json, freshToken);

        JsonNode before = kpi(overviewOf(freshToken), "adoption");
        assertThat(!before.has("value") || before.get("value").isNull()).isTrue();
        assertThat(before.get("locked").asText()).isEqualTo("no decisions");

        mvc.perform(post("/api/v1/sell/apply")
                        .header("Authorization", "Bearer " + freshToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"HRD118902\",\"store\":\"100959\"}"))
                .andExpect(status().isOk());

        JsonNode after = kpi(overviewOf(freshToken), "adoption");
        assertThat(after.has("locked") && !after.get("locked").isNull()).isFalse();
        assertThat(after.get("foot").asText()).isEqualTo("1 decisions");
        assertThat(after.get("value").asDouble()).isEqualTo(100.0);
    }

    private JsonNode overviewOf(String bearerToken) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/overview").header("Authorization", "Bearer " + bearerToken))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode kpi(JsonNode overview, String key) {
        for (JsonNode k : overview.get("kpis")) {
            if (key.equals(k.get("key").asText())) {
                return k;
            }
        }
        throw new AssertionError("No kpi with key " + key);
    }

    @Test
    @DisplayName("GET /insights/demographics: segments sum to total revenue, no Unassigned segment on the sample")
    void demographicsSegmentsSumToTotalRevenue() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/insights/demographics").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode demo = json.readTree(result.getResponse().getContentAsString());

        double total = demo.get("totalRevenue").asDouble();
        double sum = 0;
        for (JsonNode segment : demo.get("segments")) {
            sum += segment.get("revenue").asDouble();
            assertThat(segment.get("segment").asText()).isNotEqualToIgnoringCase("unassigned");
        }
        assertThat(sum).isCloseTo(total, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("a custom sales-only tenant: two stores need a region, an Unassigned segment, inventory locked")
    void customSalesOnlyTenantNeedsARegionAndHasNoInventory() throws Exception {
        String customToken = signUp(uniqueEmail("custom"));

        InputStream in = getClass().getResourceAsStream("/realdata/custom-30.template.csv");
        assertThat(in).isNotNull();
        String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = DAY_TOKEN.matcher(template);
        StringBuilder csv = new StringBuilder();
        while (m.find()) {
            long daysAgo = Long.parseLong(m.group(1));
            m.appendReplacement(csv, LocalDate.now().minusDays(daysAgo).toString());
        }
        m.appendTail(csv);

        JsonNode committed = SampleTenant.importAndCommit(mvc, json, customToken, "sales", csv.toString());
        assertThat(committed.get("status").asText()).isEqualTo("COMMITTED");

        MvcResult regionResult = mvc.perform(get("/api/v1/insights/regions/{key}", "unassigned")
                        .header("Authorization", "Bearer " + customToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode unassigned = json.readTree(regionResult.getResponse().getContentAsString());
        assertThat(unassigned.get("storeIds")).hasSize(2);

        MvcResult demoResult = mvc.perform(get("/api/v1/insights/demographics").header("Authorization", "Bearer " + customToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode demo = json.readTree(demoResult.getResponse().getContentAsString());
        boolean hasUnassigned = false;
        for (JsonNode segment : demo.get("segments")) {
            if ("unassigned".equalsIgnoreCase(segment.get("segment").asText())) {
                hasUnassigned = true;
            }
        }
        assertThat(hasUnassigned).isTrue();

        JsonNode overview = overviewOf(customToken);
        JsonNode inventoryKpi = kpi(overview, "inventory");
        assertThat(inventoryKpi.get("locked").asText()).isEqualTo("no inventory");
    }
}
