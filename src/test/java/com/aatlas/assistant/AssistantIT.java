package com.aatlas.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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
 * Ask Aatlas end to end, against a real PostgreSQL: every intent, suggestions, history - now
 * over the tenant's own real catalogue and history ({@code history.Catalogue}), not the
 * {@code seed/products.json}/{@code stores.json} fixture every tenant used to share.
 *
 * <p>{@code sellToken}'s tenant connects the Hardin sample so the data-driven intents (raise
 * prices, liquidate, hold-vs-sell, what-changed, demand-by-region, which-supplier, "a store")
 * have real sales/purchase history to answer from; {@code buyToken}'s tenant stays unconnected
 * (only persona-aware suggestion ordering is asked of it, which needs no data).
 *
 * <p>{@code which-supplier}, {@code liquidate} and {@code hold-vs-sell} still read
 * {@code bulk}'s {@link com.aatlas.bulk.SellLineReader}/{@link com.aatlas.bulk.BuyLineReader}
 * stand-ins (that module's own real-data rewrite is a sibling worktree, not merged here) - see
 * {@link com.aatlas.assistant.internal.AssistantService}'s class doc. What this worktree fixed
 * is that the assistant now resolves items/stores/regions from the tenant's own real
 * {@code history.Catalogue} rather than a fixture every tenant shared, so a natural-language
 * "copper" question or "Tell me about Dallas" always names a product/branch that is actually
 * on this tenant's books - {@code whichSupplierNamesARealItem} below pins that against the
 * sample tenant's real commodity data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aatlas.clock.fixed=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    private String sellToken;
    private String buyToken;

    private String signUp(String role) throws Exception {
        String body = """
                {
                  "fullName": "Alex Moreno",
                  "email": "assistant-%s@kestrelsupply.com",
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
        sellToken = signUp("sales-head");
        SampleTenant.connectSample(mvc, sellToken);
        SampleTenant.awaitReady(mvc, json, sellToken);
        buyToken = signUp("purchase-head");
    }

    @Test
    @DisplayName("raise prices")
    void raisePrices() throws Exception {
        ask(sellToken, "Which products should I increase prices on today?")
                .andExpect(jsonPath("$.cta.href").value("/app/sell/bulk?preset=raise"));
    }

    @Test
    @DisplayName("which supplier")
    void whichSupplier() throws Exception {
        ask(sellToken, "Which supplier should we use for a 50,000-unit copper order?")
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.startsWith("Recommended:")))
                .andExpect(jsonPath("$.cta.href").value(org.hamcrest.Matchers.containsString("/app/buy?")));
    }

    @Test
    @DisplayName("which supplier names a real item from this tenant's own catalogue, not a shared fixture")
    void whichSupplierNamesARealItem() throws Exception {
        // Every tenant used to share seed/products.json, so "copper" always resolved to the
        // same HRD118902 regardless of who asked. Now it comes from this tenant's own
        // products.commodity - the sample carries more than one real copper item (a flagship
        // tube and a low-volume coil), so pin the CTA's item against the set of this tenant's
        // own rows rather than a hardcoded string or an assumed ordering.
        UUID tenantId = SampleTenant.tenantIdOf(json, sellToken);
        List<String> realCopperItems = jdbc.queryForList(
                "select item_number from products where tenant_id = ? and commodity = 'copper' and has_sales",
                String.class, tenantId);
        assertThat(realCopperItems).isNotEmpty();
        MvcResult result = ask(sellToken, "Which supplier should we use for a 50,000-unit copper order?")
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String href = body.get("cta").get("href").asText();
        assertThat(realCopperItems.stream().anyMatch(item -> href.contains("item=" + item))).isTrue();
    }

    @Test
    @DisplayName("liquidate")
    void liquidate() throws Exception {
        ask(sellToken, "What should I liquidate?")
                .andExpect(jsonPath("$.summary").isNotEmpty());
    }

    @Test
    @DisplayName("hold vs sell")
    void holdVsSell() throws Exception {
        ask(sellToken, "Should I hold copper tube at Dallas or sell now?")
                .andExpect(jsonPath("$.cta.href").value(org.hamcrest.Matchers.containsString("panel=timing")));
    }

    @Test
    @DisplayName("what changed")
    void whatChanged() throws Exception {
        ask(sellToken, "What changed today?")
                .andExpect(jsonPath("$.title").value("Since yesterday"));
    }

    @Test
    @DisplayName("demand by region")
    void demandByRegion() throws Exception {
        ask(sellToken, "Where is demand growing?")
                .andExpect(jsonPath("$.title").value(org.hamcrest.Matchers.startsWith("Demand is growing fastest")));
    }

    @Test
    @DisplayName("a store, by city - resolves through this tenant's real branch, not a shared fixture")
    void aStore() throws Exception {
        ask(sellToken, "Tell me about Dallas")
                .andExpect(jsonPath("$.title").value("Dallas #100959"));
    }

    @Test
    @DisplayName("nothing matches: the suggested questions come back")
    void nothingMatches() throws Exception {
        ask(sellToken, "xyzzy plugh")
                .andExpect(jsonPath("$.title").value("I can answer these"))
                .andExpect(jsonPath("$.lines.length()").value(6));
    }

    @Test
    @DisplayName("suggestions are persona-aware: sell-side and buy-side seats see a different order")
    void suggestionsArePersonaAware() throws Exception {
        MvcResult sellResult = mvc.perform(get("/api/v1/assistant/suggestions")
                        .header("Authorization", "Bearer " + sellToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andReturn();
        MvcResult buyResult = mvc.perform(get("/api/v1/assistant/suggestions")
                        .header("Authorization", "Bearer " + buyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(sellResult.getResponse().getContentAsString())
                .isNotEqualTo(buyResult.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("history: recent questions come back newest first")
    void historyRecordsQuestions() throws Exception {
        ask(sellToken, "What changed today?");
        ask(sellToken, "Where is demand growing?");

        mvc.perform(get("/api/v1/assistant/history").header("Authorization", "Bearer " + sellToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("Where is demand growing?"))
                .andExpect(jsonPath("$[1].question").value("What changed today?"));
    }

    @Test
    @DisplayName("an intent on a products-only tenant (no sales history) returns the unlock message, not a hollow zero")
    void unlockMessageWithoutSalesHistory() throws Exception {
        String token = signUp("sales-head");
        String csv = """
                Item No,Description,Category,Subcategory,UOM,Commodity,List Price,Unit Cost,On Hand,Branch,Supplier,Supplier Cost,Lead Time
                NP-100,1/2 IN COPPER TYPE L HARD TUBE 10FT,Plumbing,Pipe & tube,each,copper,,19.80,240,400100,Cascade Copper Mills,17.90,12
                NP-200,3/4 IN BRASS BALL VALVE,Plumbing,Valves,each,brass,,11.20,90,400100,,,
                NP-300,2 IN PVC DWV TEE,Plumbing,Fittings,each,pvc,,4.40,600,400100,,,
                """;
        SampleTenant.importAndCommit(mvc, json, token, "products", csv);

        ask(token, "Which products should I increase prices on today?")
                .andExpect(jsonPath("$.title").value("Upload sales history to unlock this"))
                .andExpect(jsonPath("$.cta.href").value("/app/data?kind=sales"));

        // A store this tenant does have (created from the products file's Branch column) still
        // resolves normally - only the sales-driven intents are locked, never the whole feature.
        ask(token, "Which supplier should we use for a 50,000-unit copper order?")
                .andExpect(jsonPath("$.title").value("Upload sales history to unlock this"));
    }

    private org.springframework.test.web.servlet.ResultActions ask(String token, String question) throws Exception {
        return mvc.perform(post("/api/v1/assistant/ask")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("question", question))))
                .andExpect(status().isOk());
    }
}
