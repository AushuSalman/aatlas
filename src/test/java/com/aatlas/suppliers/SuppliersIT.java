package com.aatlas.suppliers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Window;
import com.aatlas.realdata.SampleOracle;
import com.aatlas.realdata.SampleTenant;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
 * The supplier panel end to end, against a real PostgreSQL: signup, seed, read every
 * endpoint, look a supplier up (honestly, finding nothing), add what was drafted, edit it,
 * and remove it - plus the real-data assertions: performance and risk observed from a
 * tenant's actual purchase history, and a supplier a purchases import created with nothing
 * invented for it.
 *
 * <p>{@code aatlas.clock.fixed=false} overrides the {@code test} profile's frozen
 * {@link com.aatlas.common.time.AatlasClock} (2026-09-01, for golden-file determinism)
 * for this class only. {@code TokenService} stamps a JWT's {@code iat}/{@code exp} from
 * that clock, but {@code JwtConfig}'s decoder validates them against the real system
 * clock; frozen at a date the environment's real clock has since passed, every token this
 * test issues would decode as already expired. No other test in the suite authenticates a
 * second request with a token it was issued, so this is the first place that mismatch is
 * visible - the override lives here, in this module's own test, rather than in the shared
 * {@code application-test.yml}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "aatlas.clock.fixed=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuppliersIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    AatlasClock clock;

    /**
     * The signup rate limiter caps one client address at ten accounts an hour
     * ({@code SignupRateLimiter}), and every MockMvc call in this test class shares the same
     * address. Three tenants, created once for the whole class rather than per test, stays
     * comfortably under that: {@code directorToken}'s tenant is seeded once in
     * {@link #setUp()} and read by every test that only needs the panel to exist;
     * {@code freshDirectorToken}'s is left unseeded so {@link #seedingIsIdempotent()} can
     * still observe "eight the first time, zero the second" on a tenant of its own.
     */
    private String directorToken;
    private String freshDirectorToken;
    private String financeToken;

    private static String uniqueEmail() {
        return "buyer-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    private String signUp(String role) throws Exception {
        String body = """
                {
                  "fullName": "Alex Moreno",
                  "email": "%s",
                  "password": "Zephyr!42Bridge",
                  "company": "Kestrel Supply Co.",
                  "country": "US",
                  "role": "%s"
                }
                """.formatted(uniqueEmail(), role);
        MvcResult result = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    @BeforeAll
    void setUp() throws Exception {
        directorToken = signUp("both");
        financeToken = signUp("finance");
        freshDirectorToken = signUp("both");

        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(8));
    }

    /** Idempotent: the shared tenant is already seeded in {@link #setUp()}, so this is a no-op. */
    private void seedPanel() throws Exception {
        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("seeding is idempotent: eight the first time, zero the second")
    void seedingIsIdempotent() throws Exception {
        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + freshDirectorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(8));

        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + freshDirectorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seeded").value(0));
    }

    @Test
    @DisplayName("only the director may seed")
    void onlyDirectorMaySeed() throws Exception {
        mvc.perform(post("/api/v1/suppliers/seed").header("Authorization", "Bearer " + financeToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_allowed"));
    }

    @Test
    @DisplayName("the panel is ranked by rating, and the two brief-pinned ratings hold")
    void panelIsRankedByRating() throws Exception {
        seedPanel();

        MvcResult result = mvc.perform(get("/api/v1/suppliers").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = json.readTree(result.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(8);

        // Ranked by rating descending.
        for (int i = 1; i < items.size(); i++) {
            assertThat(items.get(i - 1).get("rating").asDouble())
                    .isGreaterThanOrEqualTo(items.get(i).get("rating").asDouble());
        }

        JsonNode cascade = findByName(items, "Cascade Copper Mills");
        assertThat(cascade.get("rating").asDouble()).isEqualTo(4.5);
        assertThat(cascade.get("risk").has("score")).isTrue();
        assertThat(cascade.get("risk").get("assessed").asBoolean()).isTrue();
        assertThat(cascade.get("currency").asText()).isEqualTo("USD");

        JsonNode gulfStates = findByName(items, "Gulf States Polymer");
        assertThat(gulfStates.get("rating").asDouble()).isEqualTo(3.0);
    }

    private static JsonNode findByName(JsonNode items, String name) {
        for (JsonNode item : items) {
            if (name.equals(item.get("name").asText())) {
                return item;
            }
        }
        throw new AssertionError("No supplier named " + name + " in " + items);
    }

    @Test
    @DisplayName("the summary counts the panel, and rejects an unseeded ratio of zero suppliers gracefully")
    void summaryCountsThePanel() throws Exception {
        seedPanel();

        mvc.perform(get("/api/v1/suppliers/summary").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(8))
                .andExpect(jsonPath("$.added").value(0))
                .andExpect(jsonPath("$.currencies").isNumber());
    }

    @Test
    @DisplayName("one supplier by its frontend id, with terms, rating, no reviews yet, risk and performance")
    void oneSupplierByItsFrontendId() throws Exception {
        seedPanel();

        mvc.perform(get("/api/v1/suppliers/sup-2").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Cascade Copper Mills"))
                .andExpect(jsonPath("$.rating").value(4.5));

        mvc.perform(get("/api/v1/suppliers/sup-2/terms?qty=30000")
                        .header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.terms.creditDays").value(45))
                .andExpect(jsonPath("$.labels.credit").value("45 days"))
                .andExpect(jsonPath("$.watchOuts[0]").value("This order is above their 5,431 units a month capacity."));

        mvc.perform(get("/api/v1/suppliers/sup-2/rating").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4.5))
                .andExpect(jsonPath("$.label").value("Excellent"))
                .andExpect(jsonPath("$.recommendation").isNotEmpty());

        // No real review data exists anywhere in the platform today - every supplier, seeded
        // or not, answers with an empty list rather than fabricated buyer quotes.
        mvc.perform(get("/api/v1/suppliers/sup-2/reviews").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get("/api/v1/suppliers/sup-2/risk").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.level").isNotEmpty())
                .andExpect(jsonPath("$.factors").isArray());

        mvc.perform(get("/api/v1/suppliers/sup-2/performance").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otifTrend.length()").value(6))
                .andExpect(jsonPath("$.poCount12m").isNumber());
    }

    @Test
    @DisplayName("an unknown supplier is a 404")
    void unknownSupplierIsNotFound() throws Exception {
        seedPanel();
        mvc.perform(get("/api/v1/suppliers/sup-999").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }

    @Test
    @DisplayName("the web lookup fetches nothing and says so; completing it needs the same facts as adding by hand")
    void lookupThenAdd() throws Exception {
        seedPanel();

        MvcResult lookupResult = mvc.perform(post("/api/v1/suppliers/lookup")
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "Halden Metals Ltd", "country": "UK"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.query").value("Halden Metals Ltd"))
                .andExpect(jsonPath("$.country").value("UK"))
                .andExpect(jsonPath("$.draft.name").value("Halden Metals Ltd"))
                .andExpect(jsonPath("$.draft.country").value("UK"))
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.sources.length()").value(0))
                .andExpect(jsonPath("$.message")
                        .value("We don't fetch company data yet; enter what you know or import a CSV"))
                .andExpect(jsonPath("$.lookupId").isNotEmpty())
                .andReturn();
        String lookupId = json.readTree(lookupResult.getResponse().getContentAsString()).get("lookupId").asText();

        // A lookup that found nothing has nothing to add on its own: completing it takes the
        // same facts a manual entry would, over the same lookupId.
        mvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lookupId\": \"" + lookupId + "\"}"))
                .andExpect(status().isBadRequest());

        String addBody = """
                {"lookupId": "%s", "name": "Halden Metals Ltd", "country": "UK",
                 "leadTimeDays": 21, "otifPct": 92.5}
                """.formatted(lookupId);

        // Finance is not a buy seat and not the director.
        mvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("not_allowed"));

        MvcResult addResult = mvc.perform(post("/api/v1/suppliers")
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.name").value("Halden Metals Ltd"))
                .andExpect(jsonPath("$.isCustom").value(true))
                .andExpect(jsonPath("$.leadTimeDays").value(21))
                .andReturn();
        JsonNode added = json.readTree(addResult.getResponse().getContentAsString());
        String supplierId = added.get("id").asText();
        assertThat(supplierId).startsWith("own-");
        assertThat(addResult.getResponse().getContentAsString()).contains("Halden Metals Ltd");

        // Editing a custom supplier's contact.
        mvc.perform(patch("/api/v1/suppliers/{id}", supplierId)
                        .header("Authorization", "Bearer " + directorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contactName": "Jane Doe"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(supplierId));

        // A custom supplier may be removed...
        mvc.perform(delete("/api/v1/suppliers/{id}", supplierId).header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isNoContent());

        // ...a seeded one may not.
        mvc.perform(delete("/api/v1/suppliers/sup-2").header("Authorization", "Bearer " + directorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("seeded_supplier"));
    }

    @Test
    @DisplayName("on a tenant with real purchase history, sup-2's performance and risk are observed, not seeded")
    void performanceAndRiskAreObservedFromRealPurchaseHistory() throws Exception {
        String token = SampleTenant.signUpAndConnect(mvc, json, "both");
        LocalDate today = clock.today();
        SampleOracle oracle = SampleTenant.oracle(clock);
        Window w12 = Window.trailingMonths(today, 12);
        long expectedPoCount12m = oracle.purchases().stream()
                .filter(p -> "Cascade Copper Mills".equals(p.supplier()) && w12.contains(p.orderDate()))
                .count();
        // The sample gives every seeded supplier well over five received orders in the
        // trailing twelve months, which is what pushes sup-2 into "observed" mode.
        assertThat(expectedPoCount12m).isGreaterThanOrEqualTo(5);

        mvc.perform(get("/api/v1/suppliers/sup-2/performance").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poCount12m").value((int) expectedPoCount12m))
                .andExpect(jsonPath("$.otifTrend.length()").value(6));

        // Lead-time consistency, the trend label and the recent-delay factor only ever appear
        // in observed mode (RiskScoring); their presence is the proof this is real data, not
        // the provided-only fallback.
        mvc.perform(get("/api/v1/suppliers/sup-2/risk").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.consistency").isNotEmpty())
                .andExpect(jsonPath("$.factors[?(@.label=='Lead-time consistency')]").exists());

        mvc.perform(get("/api/v1/suppliers/sup-2/reviews").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/api/v1/suppliers/lookup")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "Any Supplier Inc"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false));
    }

    @Test
    @DisplayName("a supplier a purchases import creates has no invented figures, and can't be removed while it has orders")
    void purchasesImportCreatesAnUnassessedSupplier() throws Exception {
        String token = SampleTenant.signUp(mvc, json, "both", "US");
        LocalDate orderDate = clock.today().minusDays(20);
        String csv = """
                PO Number,Order Date,Supplier,Supplier Country,Item No,Item Description,Qty Ordered,Unit Cost,Freight,Duty,Landed Cost,Currency,Ship To,Promised Date,Received Date,Qty Received
                PO-9001,%1$s,Brookline Valve Co,USA,NP-900,TEST BRASS VALVE,10,25.00,1.00,0.50,26.50,USD,,,,
                PO-9001,%1$s,Brookline Valve Co,USA,NP-901,TEST BRASS FITTING,15,12.00,0.50,0.25,12.75,USD,,,,
                """.formatted(orderDate);

        SampleTenant.importAndCommit(mvc, json, token, "purchases", csv);

        MvcResult result = mvc.perform(get("/api/v1/suppliers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = json.readTree(result.getResponse().getContentAsString()).get("items");
        JsonNode brookline = findByName(items, "Brookline Valve Co");

        // Never a placeholder: no rating row exists for a supplier known only from a purchase
        // order, so every performance figure is simply absent on the wire (Jackson NON_NULL).
        assertThat(brookline.has("rating")).isFalse();
        assertThat(brookline.has("otifPct")).isFalse();
        assertThat(brookline.has("leadTimeDays")).isFalse();
        assertThat(brookline.get("risk").has("level")).isFalse();
        assertThat(brookline.get("risk").get("assessed").asBoolean()).isFalse();
        assertThat(brookline.get("isCustom").asBoolean()).isTrue();

        String supplierId = brookline.get("id").asText();
        mvc.perform(get("/api/v1/suppliers/{id}", supplierId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Brookline Valve Co"));

        // It has purchase orders on file, so it cannot be removed - even though it is "custom".
        mvc.perform(delete("/api/v1/suppliers/{id}", supplierId).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("supplier_has_purchases"));
    }
}
