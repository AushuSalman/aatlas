package com.aatlas.sell;

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
import java.math.BigDecimal;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Every {@code /api/v1/sell/*} endpoint against a real PostgreSQL and the Hardin sample
 * dataset: signup, connect the sample, exercise every read and both writes on the flagship
 * pair (Copper Tube 1/2" at Dallas #100959 - HRD118902/100959), and pin the recommendation's
 * real numbers against {@link SampleOracle} rather than any fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-sell-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SellIT extends PostgresIntegrationTest {

    private static final String ITEM = "HRD118902";
    private static final String STORE = "100959";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AatlasClock clock;

    private String token;

    private static String uniqueEmail() {
        return "seller-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    @BeforeAll
    void setUp() throws Exception {
        token = SampleTenant.signUpAndConnect(mvc, json, "both");
    }

    private String signUpAndConnectless() throws Exception {
        String body = """
                {
                  "fullName": "No Catalogue Yet",
                  "email": "%s",
                  "password": "Zephyr!42Bridge",
                  "company": "Kestrel Supply Co.",
                  "country": "US",
                  "role": "both"
                }
                """.formatted(uniqueEmail());
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @Test
    @Order(1)
    @DisplayName("a workspace with no catalogue refuses every read with 409 no_catalogue")
    void noCatalogueIsRefused() throws Exception {
        String freshToken = signUpAndConnectless();
        mvc.perform(get("/api/v1/sell/recommendation?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("no_catalogue"));
    }

    @Test
    @Order(2)
    @DisplayName("GET /sell/recommendation: real figures for the flagship pair, pinned against the sample oracle")
    void recommendation() throws Exception {
        SampleOracle oracle = SampleTenant.oracle(clock);
        Window w90 = Window.trailingDays(clock.today(), 90);
        BigDecimal expectedPrice = oracle.weightedPrice(ITEM, STORE, w90);
        SampleOracle.Band band = oracle.band(ITEM, STORE, w90);

        // The frontend's SellRecommendationResponse (platform/backend.ts) is declared
        // `extends SellIntel`: every SellIntel field belongs at the top level of this
        // response, not nested under "intel".
        MvcResult result = mvc.perform(get("/api/v1/sell/recommendation?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceable").value(true))
                .andExpect(jsonPath("$.itemNumber").value(ITEM))
                .andExpect(jsonPath("$.recommended").isNumber())
                .andExpect(jsonPath("$.sources.currentPrice").value("sales-90d"))
                .andExpect(jsonPath("$.sources.cost").value("purchases-90d"))
                .andExpect(jsonPath("$.locked", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("inventory"))))
                .andExpect(jsonPath("$.competitorCount", org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.guardrail").exists())
                .andExpect(jsonPath("$.decisionScore.total").isNumber())
                .andExpect(jsonPath("$.speedPricing.tiers.length()").value(3))
                .andExpect(jsonPath("$.holdVsSell.recommendation").isNotEmpty())
                .andExpect(jsonPath("$.opportunityScore.score").isNumber())
                .andExpect(jsonPath("$.intel").doesNotExist())
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        double currentPrice = body.get("currentPrice").asDouble();
        assertThat(currentPrice).isCloseTo(expectedPrice.doubleValue(), org.assertj.core.data.Offset.offset(0.01));
        assertThat(currentPrice).isGreaterThanOrEqualTo(band.low().doubleValue() - 0.01);
        assertThat(currentPrice).isLessThanOrEqualTo(band.high().doubleValue() + 0.01);
    }

    @Test
    @Order(3)
    @DisplayName("GET /sell/recommendation/derivation: both tiers, benchmarks, bands, steps, weights")
    void derivation() throws Exception {
        mvc.perform(get("/api/v1/sell/recommendation/derivation?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.optimal.price").isNumber())
                .andExpect(jsonPath("$.aggressive.price").isNumber())
                .andExpect(jsonPath("$.steps").isArray())
                .andExpect(jsonPath("$.weights").isArray());
    }

    @Test
    @Order(4)
    @DisplayName("GET /sell/score: the opportunity chip")
    void score() throws Exception {
        mvc.perform(get("/api/v1/sell/score?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.tier").isNotEmpty())
                .andExpect(jsonPath("$.reasons").isArray());
    }

    @Test
    @Order(5)
    @DisplayName("GET /sell/forecast and /sell/elasticity: real figures over the flagship pair's own history")
    void forecastAndElasticity() throws Exception {
        mvc.perform(get("/api/v1/sell/forecast?item=" + ITEM + "&store=" + STORE + "&horizon=operational")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceable").value(true))
                .andExpect(jsonPath("$.points.length()").value(6));

        // granularity falls back down the item-store -> item -> category -> default ladder
        // depending on how much price variation the pair's own 24-month window carries; not
        // hardcoded here since it is a real regression result over the sample's own numbers,
        // not a fixed constant.
        mvc.perform(get("/api/v1/sell/elasticity?item=" + ITEM + "&side=sell&counterparty=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coefficient").isNumber())
                .andExpect(jsonPath("$.granularity").isNotEmpty())
                .andExpect(jsonPath("$.curve").isArray());
    }

    @Test
    @Order(6)
    @DisplayName("POST /sell/scenario: a price move and a named what-if")
    void scenario() throws Exception {
        mvc.perform(post("/api/v1/sell/scenario")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\",\"pct\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceMove.price").isNumber())
                .andExpect(jsonPath("$.scenario").doesNotExist());

        mvc.perform(post("/api/v1/sell/scenario")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\",\"scenario\":\"hold-30\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario.title").isNotEmpty());

        mvc.perform(post("/api/v1/sell/scenario")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    @Order(7)
    @DisplayName("GET /sell/hold-vs-sell and /sell/speed-tiers")
    void holdVsSellAndSpeedTiers() throws Exception {
        mvc.perform(get("/api/v1/sell/hold-vs-sell?item=" + ITEM + "&store=" + STORE + "&days=30")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.lines.length()").value(7));

        mvc.perform(get("/api/v1/sell/speed-tiers?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tiers.length()").value(3));
    }

    @Test
    @Order(8)
    @DisplayName("POST /sell/quote: no write, breakdown + deal price")
    void quote() throws Exception {
        mvc.perform(post("/api/v1/sell/quote")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\",\"customerId\":\"c-1\",\"qty\":240}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealPrice").isNumber())
                .andExpect(jsonPath("$.quote.qty").value(240))
                .andExpect(jsonPath("$.customerProfile.label").isNotEmpty());
    }

    @Test
    @Order(9)
    @DisplayName("GET /sell/atp: available-to-promise allocation from real purchase-order suppliers")
    void atp() throws Exception {
        mvc.perform(get("/api/v1/sell/atp?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").isNumber())
                .andExpect(jsonPath("$.lots").isArray())
                .andExpect(jsonPath("$.lots.length()", org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.lines").isArray());
    }

    @Test
    @Order(10)
    @DisplayName("GET /sell/starters: top three real opportunities for the empty state")
    void starters() throws Exception {
        // Field names, not just shape: the frontend's SellStarter (platform/backend.ts) names
        // these item/store/pct, not itemNumber/storeId/upliftPct.
        mvc.perform(get("/api/v1/sell/starters").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isNotEmpty())
                .andExpect(jsonPath("$[0].item").exists())
                .andExpect(jsonPath("$[0].store").exists())
                .andExpect(jsonPath("$[0].pct").exists());
    }

    @Test
    @Order(50)
    @DisplayName("POST /sell/apply then POST /sell/quotes: each writes a decision GET /sell/outcomes reads back")
    // Runs last on purpose: apply() now writes the applied price through history.PriceBook
    // (source "applied"), which becomes the pair's new price-list rung and would otherwise
    // change sources.currentPrice for every read test above from sales-90d to price-list.
    void applyThenQuoteThenOutcomes() throws Exception {
        mvc.perform(post("/api/v1/sell/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.id").isNotEmpty())
                .andExpect(jsonPath("$.decision.kind").value("sell"));

        mvc.perform(post("/api/v1/sell/quotes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"item\":\"" + ITEM + "\",\"store\":\"" + STORE + "\",\"customerId\":\"c-3\",\"qty\":120}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision.id").isNotEmpty());

        mvc.perform(get("/api/v1/sell/outcomes?item=" + ITEM + "&store=" + STORE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
    }

    @Test
    @Order(20)
    @DisplayName("an item with no sales history and no price at a store is a 404 for the tool endpoints")
    void notPriceableLineIsNotFound() throws Exception {
        String freshToken = signUpAndConnectless();
        SampleTenant.importAndCommit(mvc, json, freshToken, "products",
                "Item No,Description,On Hand\nNP-900,UNSOLD WIDGET,5\n");

        mvc.perform(get("/api/v1/sell/speed-tiers?item=NP-900&store=MAIN")
                        .header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(21)
    @DisplayName("a products-only tenant with a wizard price is priceable, source price-list, forecast and demand locked")
    void productsOnlyTenantWithWizardPrice() throws Exception {
        String freshToken = signUpAndConnectless();
        SampleTenant.importAndCommit(mvc, json, freshToken, "products",
                "Item No,Description,Category,Subcategory,UOM,Commodity,Unit Cost,On Hand,Branch\n"
                        + "NP-100,1/2 IN COPPER TYPE L HARD TUBE 10FT,Plumbing,Pipe & tube,each,copper,17.90,240,400100\n");

        mvc.perform(post("/api/v1/prices/bulk")
                        .header("Authorization", "Bearer " + freshToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rows\":[{\"item\":\"NP-100\",\"listPrice\":24.90}],\"source\":\"wizard\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/sell/recommendation?item=NP-100&store=400100")
                        .header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceable").value(true))
                .andExpect(jsonPath("$.sources.currentPrice").value("price-list"))
                .andExpect(jsonPath("$.locked", org.hamcrest.Matchers.hasItem("forecast")))
                .andExpect(jsonPath("$.locked", org.hamcrest.Matchers.hasItem("demand")));
    }
}
