package com.aatlas.buy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Iterator;
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
 * Pins the buy engines against {@code golden/buy-recommendation.json}, {@code
 * golden/buy-intel.json} and {@code golden/procurement-plan.json}, produced by running the
 * TypeScript prototype's own {@code buildBuyRecommendation}, {@code getBuyIntel}, {@code
 * procurementPlan} and {@code supplierRisk}.
 *
 * <p>Unlike wave 1's {@code SupplierEngineGoldenTest} (pure functions, fed straight from
 * {@code seed/suppliers.json}, no database), this port reads the tenant's real catalogue and
 * supplier panel through {@code CatalogGateway}/{@code SupplierGateway} - real Postgres rows
 * seeded the same way a demo tenant is. So this is an {@code *IT} against a real database
 * (Testcontainers), driven through the actual HTTP endpoints, exactly as the frontend calls
 * them: the golden-file proof and the "does the wiring work end to end" proof in one place.
 *
 * <p>The flagship story is checked explicitly in {@link #flagshipUrgentBuyRecommendsCascadeWhileGulfStatesIsCheapest()}:
 * for the Copper Tube item at Dallas, urgent priority, Cascade Copper Mills is recommended
 * from stock while Gulf States Polymer is cheapest on paper.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=aatlas-api-buy-golden-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuyEngineGoldenIT extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private String token;

    private static String uniqueEmail() {
        return "buyer-" + UUID.randomUUID() + "@kestrelsupply.com";
    }

    @BeforeAll
    void connectedTenant() throws Exception {
        MvcResult signup = mvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fullName": "Priya Raghavan",
                                  "email": "%s",
                                  "password": "Zephyr!42Bridge",
                                  "company": "Kestrel Supply Co.",
                                  "country": "US",
                                  "role": "both"
                                }
                                """.formatted(uniqueEmail())))
                .andExpect(status().isCreated())
                .andReturn();
        token = json.readTree(signup.getResponse().getContentAsString()).get("accessToken").asText();

        mvc.perform(post("/api/v1/data-sources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"sample\"}"))
                .andExpect(status().isCreated());
        waitForSupplierPanel();
    }

    /**
     * The supplier panel is seeded by {@code SuppliersSeedListener}, an
     * {@code @ApplicationModuleListener} that runs asynchronously after {@code
     * SampleDataConnected} commits - not inside the {@code POST /data-sources} request. Calling
     * {@code POST /suppliers/seed} immediately afterwards (as {@code SuppliersIT} does on its
     * own, unconnected tenant) races that listener here: both read "no panel yet" and both
     * insert, and the second commit loses to {@code suppliers_tenant_key_uk}. Polling the read
     * side instead - not writing - has no such race.
     */
    private void waitForSupplierPanel() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            MvcResult result = mvc.perform(get("/api/v1/suppliers/summary").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            if (json.readTree(result.getResponse().getContentAsString()).get("size").asInt() >= 8) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Supplier panel was not seeded within 10s of connecting the sample data source");
    }

    private static JsonNode golden(String file) throws Exception {
        try (InputStream in = BuyEngineGoldenIT.class.getResourceAsStream("/golden/" + file)) {
            assertThat(in).as("golden/%s on the test classpath", file).isNotNull();
            return PLAIN.readTree(in);
        }
    }

    private JsonNode getJson(String path, String... params) throws Exception {
        var req = get(path).header("Authorization", "Bearer " + token);
        for (int i = 0; i < params.length; i += 2) {
            if (params[i + 1] != null) {
                req = req.param(params[i], params[i + 1]);
            }
        }
        MvcResult result = mvc.perform(req).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Structural equality that treats {@code 4} and {@code 4.0} as the same value - see
     * {@code SupplierEngineGoldenTest} for why (a Java {@code double} always serialises with
     * a decimal point; {@code JSON.stringify} of a whole-number JS number does not).
     */
    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return (a == null || a.isNull()) && (b == null || b.isNull());
        }
        if (a.isNumber() && b.isNumber()) {
            return a.asDouble() == b.asDouble();
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<String> names = a.fieldNames();
            while (names.hasNext()) {
                String f = names.next();
                if (!b.has(f) || !jsonEquals(a.get(f), b.get(f))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static void assertGoldenEquals(JsonNode expected, JsonNode actual, String label) {
        assertThat(jsonEquals(actual, expected))
                .as("%s%nexpected=%s%nactual=%s", label, expected, actual)
                .isTrue();
    }

    @Test
    @DisplayName("buildBuyRecommendation reproduces every (item, destination) row, the landed-cost panel")
    void reproducesEveryBuyRecommendation() throws Exception {
        JsonNode rows = golden("buy-recommendation.json");
        assertThat(rows).hasSize(135);
        for (JsonNode row : rows) {
            JsonNode input = row.get("input");
            String item = input.get("itemNumber").asText();
            String destination = input.get("destinationId").asText();
            JsonNode actual = getJson("/api/v1/buy/recommendation", "item", item, "destination", destination);
            assertGoldenEquals(row.get("output"), actual, "buildBuyRecommendation(%s, %s)".formatted(item, destination));
        }
    }

    @Test
    @DisplayName("getBuyIntel reproduces every (item, region, qty) row: effective cost, now-vs-wait, negotiation")
    void reproducesEveryBuyIntel() throws Exception {
        JsonNode rows = golden("buy-intel.json");
        assertThat(rows).hasSize(180);
        for (JsonNode row : rows) {
            JsonNode input = row.get("input");
            String item = input.get("itemNumber").asText();
            String region = input.get("regionKey").asText();
            String qty = input.get("qty").asText();
            JsonNode actual = getJson("/api/v1/buy/intel", "item", item, "region", region, "qty", qty);
            assertGoldenEquals(row.get("output"), actual,
                    "getBuyIntel(%s, %s, %s)".formatted(item, region, qty));
        }
    }

    @Test
    @DisplayName("procurementPlan reproduces every (requiredDays, priority) row for the flagship item/region/qty")
    void reproducesEveryProcurementPlan() throws Exception {
        JsonNode rows = golden("procurement-plan.json");
        for (JsonNode row : rows) {
            if (!"procurementPlan".equals(row.get("fn").asText())) {
                continue;
            }
            JsonNode input = row.get("input");
            String requiredDays = String.valueOf(input.get("requiredDays").asInt());
            String priority = input.get("priority").asText();
            JsonNode actual = getJson("/api/v1/buy/plan",
                    "item", "HRD118902", "region", "south", "qty", "2400",
                    "requiredDays", requiredDays, "priority", priority);
            assertGoldenEquals(row.get("output"), actual,
                    "procurementPlan(requiredDays=%s, priority=%s)".formatted(requiredDays, priority));
        }
    }

    @Test
    @DisplayName("supplierRisk reproduces every seeded supplier's fulfilment risk, embedded in a plan's ranked list")
    void reproducesEverySupplierRisk() throws Exception {
        // supplierRisk is not its own endpoint - it is embedded per supplier in
        // ProcurementPlan.ranked[].risk. Any plan for the same (item, region, qty) carries
        // the same risk per supplier, since it depends only on the supplier's own facts.
        JsonNode plan = getJson("/api/v1/buy/plan",
                "item", "HRD118902", "region", "south", "qty", "2400", "requiredDays", "7", "priority", "balanced");
        JsonNode ranked = plan.get("ranked");

        for (JsonNode row : golden("procurement-plan.json")) {
            if (!"supplierRisk".equals(row.get("fn").asText())) {
                continue;
            }
            String supplierId = row.get("input").get("s").get("supplierId").asText();
            JsonNode actual = null;
            for (JsonNode r : ranked) {
                if (supplierId.equals(r.get("s").get("supplierId").asText())) {
                    actual = r.get("risk");
                    break;
                }
            }
            assertThat(actual).as("no ranked row for %s", supplierId).isNotNull();
            assertGoldenEquals(row.get("output"), actual, "supplierRisk(" + supplierId + ")");
        }
    }

    @Test
    @DisplayName("flagship: urgent Copper Tube buy at Dallas recommends Cascade Copper Mills from stock, "
            + "while Gulf States Polymer is cheapest on paper")
    void flagshipUrgentBuyRecommendsCascadeWhileGulfStatesIsCheapest() throws Exception {
        JsonNode plan = getJson("/api/v1/buy/plan",
                "item", "HRD118902", "region", "south", "qty", "2400", "requiredDays", "2", "priority", "balanced");

        assertThat(plan.get("urgency").asText()).isEqualTo("urgent");
        JsonNode recommended = plan.get("recommended");
        assertThat(recommended.get("suppliers").get(0).get("name").asText()).isEqualTo("Cascade Copper Mills");
        assertThat(recommended.get("suppliers").get(0).get("route").asText()).isEqualTo("From stock, 1 day");
        assertThat(recommended.get("key").asText()).isEqualTo("speed");

        JsonNode cheapest = plan.get("options").get(0);
        assertThat(cheapest.get("key").asText()).isEqualTo("cost");
        assertThat(cheapest.get("suppliers").get(0).get("name").asText()).isEqualTo("Gulf States Polymer");
        assertThat(cheapest.get("unitCost").asDouble()).isLessThan(recommended.get("unitCost").asDouble());

        // Cross-checked against the recorded golden row for the same inputs.
        for (JsonNode row : golden("procurement-plan.json")) {
            JsonNode input = row.get("input");
            if ("procurementPlan".equals(row.get("fn").asText()) && input.get("requiredDays").asInt() == 2
                    && "balanced".equals(input.get("priority").asText())) {
                assertGoldenEquals(row.get("output"), plan, "procurementPlan(requiredDays=2, priority=balanced)");
            }
        }
    }
}
