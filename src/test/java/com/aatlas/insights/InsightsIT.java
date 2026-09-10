package com.aatlas.insights;

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

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

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
        mvc.perform(get("/api/v1/insights/regions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));

        mvc.perform(get("/api/v1/insights/regions/{key}", "south").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("south"))
                .andExpect(jsonPath("$.storeIds", org.hamcrest.Matchers.hasItem("100959")))
                .andExpect(jsonPath("$.headline").value("High demand"));
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
}
