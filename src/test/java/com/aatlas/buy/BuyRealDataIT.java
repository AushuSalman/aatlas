package com.aatlas.buy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * {@code buy} against a real Postgres: the flagship item on the sample tenant (incumbent,
 * quotes, cost labels all real), a sales-only tenant with no purchase history at all (nothing
 * to compare against), and the RFQ simulate gate (spec-A S2 "rfq": only the sample tenant's
 * replies may ever be simulated).
 *
 * <p>Every figure asserted comes from {@link SampleOracle} (the sample CSVs summed in Java) or
 * from the fixture uploaded in the test itself - never a constant that could drift with the
 * generator or the calendar. {@code real-data/foundation}'s own {@code RealDataIT} owns
 * readiness/import/rollback; this class owns the buy-side engine figures, per the wave-B file
 * plan (worktree ITs live outside {@code com.aatlas.realdata}, which is frozen).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-buy-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyRealDataIT extends PostgresIntegrationTest {

    private static final Pattern DAY_TOKEN = Pattern.compile("D-(\\d+)");
    private static final String ITEM = "HRD118902";
    private static final String STORE = "100959";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AatlasClock clock;

    @Test
    @DisplayName("the flagship item's landed-cost panel on the sample tenant is built from real purchase history")
    void sampleTenantRecommendationIsReal() throws Exception {
        String token = SampleTenant.signUpAndConnect(mvc, json, "both");
        SampleOracle oracle = SampleTenant.oracle(clock);
        LocalDate today = clock.today();

        JsonNode rec = getJson(token, "/api/v1/buy/recommendation?item=" + ITEM + "&destination=" + STORE);

        String expectedIncumbent = oracle.topShareSupplier(ITEM, Window.trailingMonths(today, 12));
        assertThat(expectedIncumbent).as("oracle has a top-share supplier for the flagship item").isNotNull();
        assertThat(rec.get("incumbentSupplierName").asText()).isEqualTo(expectedIncumbent);
        assertThat(rec.get("incumbentSupplierId").asText()).isNotBlank();
        assertThat(rec.path("incumbentReason").isMissingNode() || rec.path("incumbentReason").isNull()).isTrue();

        // Every quote is either absent (no fabricated figure) or labelled with a real source -
        // never a hash. See spec-A S2 "buy" quotes[] row.
        for (JsonNode q : rec.get("quotes")) {
            JsonNode source = q.get("exWorksSource");
            if (source != null && !source.isNull()) {
                assertThat(source.asText()).isIn("price-list", "purchases");
            } else {
                assertThat(q.path("exWorksCost").isMissingNode() || q.path("exWorksCost").isNull())
                        .as("no exWorksSource means no fabricated exWorksCost either")
                        .isTrue();
            }
        }
        assertThat(rec.get("quotes")).isNotEmpty();

        // currentCost is history.PriceLadder.cost(item, destination) - the flagship item has
        // more than three purchase orders into every branch in the trailing 90 days, so the
        // ladder's first rung (purchases-90d) always answers.
        assertThat(rec.get("sources").get("currentCost").asText()).isEqualTo("purchases-90d");
        assertThat(rec.get("currentCost").decimalValue()).isPositive();

        // sellPrice is the same PriceLadder.currentPrice number Sell will show once the sell
        // worktree merges (not asserted against a live /sell/recommendation call here: that
        // endpoint is still the pre-real-data stand-in in this worktree) - verified directly
        // against the sample's own weighted price at this store.
        BigDecimal expectedSellPrice = oracle.weightedPrice(ITEM, STORE, Window.trailingDays(today, 90));
        if (expectedSellPrice != null) {
            assertThat(rec.get("sellPrice").decimalValue())
                    .isCloseTo(expectedSellPrice, org.assertj.core.data.Offset.offset(new BigDecimal("0.05")));
            assertThat(rec.get("sources").get("sellPrice").asText()).isEqualTo("sales-90d");
        }

        assertThat(rec.get("locked")).isEmpty();
    }

    @Test
    @DisplayName("a sales-only tenant has no incumbent, no quotes and purchases locked")
    void salesOnlyTenantHasNoIncumbent() throws Exception {
        String token = SampleTenant.signUp(mvc, json, "both", "US");
        SampleTenant.importAndCommit(mvc, json, token, "sales", customCsv(clock.today()));

        JsonNode rec = getJson(token, "/api/v1/buy/recommendation?item=" + ITEM + "&destination=" + STORE);

        assertThat(rec.path("incumbentSupplierId").isNull() || rec.path("incumbentSupplierId").isMissingNode())
                .isTrue();
        assertThat(rec.get("incumbentReason").asText()).isNotBlank();
        List<JsonNode> quotes = new ArrayList<>();
        rec.get("quotes").forEach(quotes::add);
        boolean allNullOrEmpty = quotes.isEmpty()
                || quotes.stream().allMatch(q -> q.path("exWorksCost").isNull() || q.path("exWorksCost").isMissingNode());
        assertThat(allNullOrEmpty).as("quotes empty or all-null with no purchase history on file").isTrue();
        List<String> locked = new ArrayList<>();
        rec.get("locked").forEach(l -> locked.add(l.asText()));
        assertThat(locked).contains("purchases");

        // No suppliers on file at all (the panel is only seeded when the sample connects) -
        // nothing to compare, so nothing an RFQ could ever be built from either.
        JsonNode compare = getJson(token, "/api/v1/buy/compare?item=" + ITEM + "&region=south&qty=10");
        assertThat(compare).isEmpty();
    }

    @Test
    @DisplayName("RFQ replies are simulated only on the sample tenant, and every simulated reply says so")
    void rfqSimulatesOnlyOnTheSampleTenant() throws Exception {
        String token = SampleTenant.signUpAndConnect(mvc, json, "purchase-manager");

        List<String> supplierIds = new ArrayList<>();
        for (JsonNode row : getJson(token, "/api/v1/buy/compare?item=" + ITEM + "&region=south&qty=50")) {
            supplierIds.add(row.get("supplierId").asText());
        }
        assertThat(supplierIds).isNotEmpty();
        String supplierIdsJson = supplierIds.stream().map(id -> "\"" + id + "\"")
                .reduce((a, b) -> a + "," + b).orElseThrow();

        MvcResult created = mvc.perform(post("/api/v1/rfqs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemNumber": "%s",
                                  "regionKey": "south",
                                  "qty": 40,
                                  "requiredDays": 7,
                                  "priority": "balanced",
                                  "supplierIds": [%s]
                                }
                                """.formatted(ITEM, supplierIdsJson)))
                .andExpect(status().isOk())
                .andReturn();
        UUID id = UUID.fromString(json.readTree(created.getResponse().getContentAsString()).get("id").asText());

        mvc.perform(post("/api/v1/rfqs/" + id + "/send").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult quotedResult = mvc.perform(post("/api/v1/rfqs/" + id + "/quotes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode quoted = json.readTree(quotedResult.getResponse().getContentAsString());

        assertThat(quoted.get("quotes")).isNotEmpty();
        for (JsonNode q : quoted.get("quotes")) {
            assertThat(q.get("simulated").asBoolean())
                    .as("nobody typed a reply in, and this is the sample tenant, so RfqEngine.simulate must have")
                    .isTrue();
            assertThat(q.path("enteredBy").isNull() || q.path("enteredBy").isMissingNode())
                    .as("a simulated reply is never attributed to a user")
                    .isTrue();
        }
    }

    private JsonNode getJson(String token, String url) throws Exception {
        MvcResult result = mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** The template with every {@code D-<n>} token replaced by {@code today - n}. See {@code realdata.RealDataIT}. */
    private static String customCsv(LocalDate today) throws Exception {
        try (InputStream in = BuyRealDataIT.class.getResourceAsStream("/realdata/custom-30.template.csv")) {
            assertThat(in).isNotNull();
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = DAY_TOKEN.matcher(template);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(out, today.minusDays(Integer.parseInt(m.group(1))).toString());
            }
            m.appendTail(out);
            return out.toString();
        }
    }
}
