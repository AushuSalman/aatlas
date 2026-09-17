package com.aatlas.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
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
 * Bulk sell and bulk buy end to end, against a real PostgreSQL and the Hardin sample
 * tenant: signup, connect, plan, apply, both sides. Every pricing/cost assertion here is a
 * <b>consistency</b> check against the single-item screens ({@code /sell/recommendation},
 * {@code /buy/intel}) rather than a hard-coded figure - bulk merges independently of the
 * sell and buy worktrees, so the numbers themselves may still be on placeholder data; what
 * must always hold is that bulk and single-item agree with each other. See {@code
 * aatlas.clock.fixed=false}'s note on {@code SuppliersIT} for why this override is needed
 * whenever a test authenticates more than one request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aatlas.clock.fixed=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BulkIT extends PostgresIntegrationTest {

    private static final String STORE = "100959";
    private static final String REGION = "south";
    private static final String PAIR_ITEM = "HRD118902";
    private static final String BASKET = "HRD118902,HRD304148,HRD772310,HRD450871,HRD661204,"
            + "HRD512066,HRD874019,HRD248813,HRD335590,HRD107744,HRD983377,HRD290145";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private String token;
    private String financeToken;

    @BeforeAll
    void setUp() throws Exception {
        token = SampleTenant.signUpAndConnect(mvc, json, "both");
        // finance has bulk=false in seed/personas.json: cannot apply a bulk strategy.
        financeToken = SampleTenant.signUp(mvc, json, "finance", "US");
    }

    @Test
    @DisplayName("sell/bulk/plan line agrees with /sell/recommendation for the same pair")
    void sellBulkPlanAgreesWithSellRecommendation() throws Exception {
        JsonNode rec = getJson("/api/v1/sell/recommendation", token,
                "item", PAIR_ITEM, "store", STORE);
        assertThat(rec.get("priceable").asBoolean()).isTrue();

        JsonNode plan = getJson("/api/v1/sell/bulk/plan", token, "store", STORE, "items", PAIR_ITEM);
        assertThat(plan.get("lines")).hasSize(1);
        JsonNode line = plan.get("lines").get(0);

        assertThat(line.get("cost").asDouble()).isCloseTo(rec.get("cost").asDouble(), offset(0.01));
        assertThat(line.get("current").asDouble()).isCloseTo(rec.get("currentPrice").asDouble(), offset(0.01));
        assertThat(line.get("recommended").asDouble()).isCloseTo(rec.get("recommended").asDouble(), offset(0.01));
    }

    @Test
    @DisplayName("buy/bulk/plan line agrees with /buy/intel for the same item")
    void buyBulkPlanAgreesWithBuyIntel() throws Exception {
        JsonNode intel = getJson("/api/v1/buy/intel", token,
                "item", PAIR_ITEM, "region", REGION, "qty", "100");
        assertThat(intel.get("priceable").asBoolean()).isTrue();

        JsonNode plan = getJson("/api/v1/buy/bulk/plan", token, "region", REGION, "items", PAIR_ITEM);
        assertThat(plan.get("lines")).hasSize(1);
        JsonNode line = plan.get("lines").get(0);

        assertThat(line.get("incumbent").get("landed").asDouble())
                .isCloseTo(intel.get("incumbent").get("landed").asDouble(), offset(0.01));

        Map<String, Double> planLanded = new HashMap<>();
        for (JsonNode s : line.get("suppliers")) {
            planLanded.put(s.get("supplierId").asText(), s.get("landed").asDouble());
        }
        for (JsonNode s : intel.get("suppliers")) {
            String supplierId = s.get("supplierId").asText();
            assertThat(planLanded).containsKey(supplierId);
            assertThat(planLanded.get(supplierId)).isCloseTo(s.get("landed").asDouble(), offset(0.01));
        }
    }

    @Test
    @DisplayName("sell/bulk: three strategies in order, a valid recommendation, then apply")
    void sellBulkPlanThenApply() throws Exception {
        MvcResult planResult = mvc.perform(get("/api/v1/sell/bulk/plan")
                        .param("store", STORE)
                        .param("items", BASKET)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(STORE))
                .andExpect(jsonPath("$.strategies.length()").value(3))
                .andExpect(jsonPath("$.strategies[0].key").value("max-profit"))
                .andExpect(jsonPath("$.strategies[1].key").value("fast-movement"))
                .andExpect(jsonPath("$.strategies[2].key").value("balanced"))
                .andExpect(jsonPath("$.recommendedKey").isNotEmpty())
                .andReturn();
        JsonNode plan = json.readTree(planResult.getResponse().getContentAsString());
        assertThat(plan.get("lines")).isNotEmpty();
        String recommendedKey = plan.get("recommendedKey").asText();
        assertThat(recommendedKey).isIn("max-profit", "fast-movement", "balanced");

        mvc.perform(post("/api/v1/sell/bulk/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storeId": "%s", "items": ["%s"], "strategyKey": "%s"}
                                """.formatted(STORE, PAIR_ITEM, recommendedKey)))
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
                                {"storeId": "%s", "items": ["%s"], "strategyKey": "balanced"}
                                """.formatted(STORE, PAIR_ITEM)))
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
                                {"storeId": "%s", "items": ["%s"], "strategyKey": "not-a-strategy"}
                                """.formatted(STORE, PAIR_ITEM)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("buy/bulk: five strategies in order, a valid recommendation, then apply")
    void buyBulkPlanThenApply() throws Exception {
        MvcResult planResult = mvc.perform(get("/api/v1/buy/bulk/plan")
                        .param("region", REGION)
                        .param("items", BASKET)
                        .param("horizon", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regionKey").value(REGION))
                .andExpect(jsonPath("$.strategies.length()").value(5))
                .andExpect(jsonPath("$.strategies[0].key").value("lowest-cost"))
                .andExpect(jsonPath("$.strategies[1].key").value("fastest"))
                .andExpect(jsonPath("$.strategies[2].key").value("lowest-risk"))
                .andExpect(jsonPath("$.strategies[3].key").value("balanced"))
                .andExpect(jsonPath("$.strategies[4].key").value("split"))
                .andReturn();
        JsonNode plan = json.readTree(planResult.getResponse().getContentAsString());
        assertThat(plan.get("lines")).isNotEmpty();
        String recommendedKey = plan.get("recommendedKey").asText();
        assertThat(recommendedKey).isIn("lowest-cost", "balanced");

        mvc.perform(post("/api/v1/buy/bulk/apply")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"regionKey": "%s", "items": ["%s"], "strategyKey": "split", "horizon": 1}
                                """.formatted(REGION, PAIR_ITEM)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionId").isNotEmpty())
                .andExpect(jsonPath("$.appliedStrategy").value("split"))
                .andExpect(jsonPath("$.plan.strategies.length()").value(5));
    }

    /**
     * A products-only tenant (price and cost on file, no On Hand, then one sale so the
     * still-placeholder sell engine considers the item priceable - see the class doc)
     * lists the item in its bulk sell plan with no as-of date for stock, because {@link
     * com.aatlas.bulk.internal.BulkSellEngine} reads {@code inventoryAsOf} straight off
     * {@code history.BulkModelReader}/{@code Inventory}, independent of sell's own
     * pipeline. What this test cannot yet assert end to end: {@code sell.SellLines}'s
     * shipped implementation ({@code sell.internal.SellLinesImpl}) is still wave B's
     * placeholder and always returns {@code locked = []}, whatever the real inventory
     * position is - {@code com.aatlas.bulk.internal.BulkSellEngineTest} unit-tests bulk's
     * own "{@code locked} contains {@code inventory} -> baseUnits from monthlyUnits,
     * opportunity null" handling directly over a fake line, and this same block should
     * additionally assert {@code locked} contains {@code "inventory"} once sell's real
     * {@code SellLinesImpl} lands and starts reporting it.
     */
    @Test
    @DisplayName("a products-only tenant's bulk sell plan has no inventory as-of date when no On Hand was uploaded")
    void productsOnlyTenantHasNoInventoryAsOf() throws Exception {
        String productsToken = SampleTenant.signUp(mvc, json, "both", "US");
        String item = "BLK" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
        String productsCsv = """
                Item No,Description,Category,Subcategory,UOM,Commodity,List Price,Unit Cost,Branch
                %s,BULK TEST WIDGET,Plumbing,Fittings,each,pvc,25.00,14.00,500100
                """.formatted(item);
        SampleTenant.importAndCommit(mvc, json, productsToken, "products", productsCsv);

        // One sale so the item clears the (still placeholder) sell engine's hasSales gate,
        // without ever uploading an On Hand figure for it.
        String salesCsv = """
                Item No,Invoice Date,Qty Shipped,Net Price,Whse
                %s,%s,5,25.00,500100
                """.formatted(item, java.time.LocalDate.now().minusDays(30));
        SampleTenant.importAndCommit(mvc, json, productsToken, "sales", salesCsv);

        JsonNode plan = getJson("/api/v1/sell/bulk/plan", productsToken, "store", "500100", "items", item);
        assertThat(plan.get("lines")).hasSize(1);
        JsonNode line = plan.get("lines").get(0);
        assertThat(line.get("itemNumber").asText()).isEqualTo(item);
        JsonNode inventoryAsOf = line.get("inventoryAsOf");
        assertThat(inventoryAsOf == null || inventoryAsOf.isNull()).isTrue();
    }

    private JsonNode getJson(String path, String bearerToken, String... params) throws Exception {
        var request = get(path).header("Authorization", "Bearer " + bearerToken);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
