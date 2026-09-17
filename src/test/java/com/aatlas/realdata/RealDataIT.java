package com.aatlas.realdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.smoke.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Real data end to end: the sample tenant loaded through the import path, a tenant built
 * from a 30-row CSV, a tenant with a product master and no history, and a rollback.
 *
 * <p>Every figure asserted comes from {@link SampleOracle} (the sample CSVs summed in Java)
 * or from the fixture itself, never from a constant that could drift with the generator.
 * This class owns readiness, import, rollback and price-list assertions; engine figures live
 * in each module's own IT. Runs on the real clock so {@code SampleDates} is exercised.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.application.name=real-data-it",
    "aatlas.clock.fixed=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RealDataIT extends PostgresIntegrationTest {

    private static final Pattern DAY_TOKEN = Pattern.compile("D-(\\d+)");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AatlasClock clock;

    private String customToken;
    private UUID customTenant;
    private UUID customBatch;

    @Test
    @Order(1)
    @DisplayName("the sample tenant: 26 months of Hardin history through the import path")
    void sampleTenant() throws Exception {
        String token = SampleTenant.signUp(mvc, json, "both", "US");
        UUID tenantId = SampleTenant.tenantIdOf(json, token);
        SampleTenant.connectSample(mvc, token);
        JsonNode readiness = SampleTenant.awaitReady(mvc, json, token);
        SampleOracle oracle = SampleTenant.oracle(clock);

        assertThat(readiness.get("dataSource").get("kind").asText()).isEqualTo("sample");
        assertThat(readiness.get("sales").get("rows").asLong()).isEqualTo(oracle.salesRows());
        assertThat(readiness.get("purchases").get("rows").asLong()).isEqualTo(oracle.purchaseRows());
        assertThat(readiness.get("purchases").get("received").asLong()).isEqualTo(oracle.receivedRows());
        assertThat(readiness.get("catalogue").get("products").asInt()).isEqualTo(17);
        assertThat(readiness.get("catalogue").get("customersUnassigned").asInt()).isZero();
        assertThat(readiness.get("prices").get("itemsWithCost").asInt()).isEqualTo(17);
        assertThat(readiness.get("inventory").get("items").asInt()).isEqualTo(17);
        assertThat(readiness.get("competitorPrices").get("items").asInt()).isEqualTo(17);
        assertThat(readiness.get("suppliers").get("count").asInt()).isEqualTo(8);
        assertThat(readiness.get("suppliers").get("withPurchases").asInt()).isEqualTo(8);
        assertThat(readiness.get("sales").get("latest").asText()).isEqualTo(clock.today().toString());

        for (JsonNode feature : readiness.get("features")) {
            String expected = "history".equals(feature.get("key").asText()) ? "partial" : "ready";
            assertThat(feature.get("status").asText()).as(feature.get("key").asText()).isEqualTo(expected);
        }

        Integer purchaseRows = jdbc.queryForObject(
                "select count(*) from purchase_order where tenant_id = ? and source = 'sample'", Integer.class, tenantId);
        assertThat(purchaseRows).isEqualTo(oracle.purchaseRows());
        Integer deals = jdbc.queryForObject("select count(*) from deal where tenant_id = ?", Integer.class, tenantId);
        assertThat(deals).isZero();
        // Every PO landed on the seeded panel, never on a supplier the import had to invent.
        List<String> supplierKeys = jdbc.queryForList(
                "select distinct supplier_id from purchase_order where tenant_id = ?", String.class, tenantId);
        assertThat(supplierKeys).allMatch(k -> k.matches("sup-[1-8]"));
        Integer openRows = jdbc.queryForObject(
                "select count(*) from purchase_order where tenant_id = ? and status = 'open'", Integer.class, tenantId);
        assertThat(openRows).isGreaterThan(0);
        // Sample dates end today and never pass it.
        LocalDate latestSale = jdbc.queryForObject(
                "select max(txn_date) from sales_transactions where tenant_id = ?", LocalDate.class, tenantId);
        assertThat(latestSale).isEqualTo(clock.today());
    }

    @Test
    @Order(2)
    @DisplayName("a custom tenant built from a 30-row sales CSV")
    void customCsvTenant() throws Exception {
        customToken = SampleTenant.signUp(mvc, json, "both", "US");
        customTenant = SampleTenant.tenantIdOf(json, customToken);

        JsonNode uploaded = SampleTenant.upload(mvc, json, customToken, "sales", customCsv(clock.today()));
        assertThat(uploaded.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(uploaded.get("acceptedRows").asInt()).isEqualTo(30);
        assertThat(uploaded.get("issueCount").asLong()).isEqualTo(1);
        assertThat(uploaded.get("issues").get(0).get("severity").asText()).isEqualTo("warning");
        assertThat(uploaded.get("isSample").asBoolean()).isFalse();
        customBatch = UUID.fromString(uploaded.get("id").asText());

        mvc.perform(post("/api/v1/imports/{id}/commit", customBatch).header("Authorization", "Bearer " + customToken))
                .andExpect(status().isAccepted());
        JsonNode committed = SampleTenant.awaitCommitted(mvc, json, customToken, customBatch);
        assertThat(committed.get("loadedRows").asInt()).isEqualTo(30);
        assertThat(committed.get("productsCreated").asInt()).isEqualTo(4);
        assertThat(committed.get("branchesCreated").asInt()).isEqualTo(2);
        assertThat(committed.get("summary").get("customersCreated").asInt()).isEqualTo(4);

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + customToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataSource.kind").value("csv"));

        JsonNode readiness = SampleTenant.readiness(mvc, json, customToken);
        assertThat(readiness.get("catalogue").get("storesUnassigned").asInt()).isEqualTo(2);
        assertThat(readiness.get("catalogue").get("customersUnassigned").asInt()).isEqualTo(4);
        assertThat(readiness.get("sales").get("items").asInt()).isEqualTo(4);
        assertThat(readiness.get("sales").get("rows").asInt()).isEqualTo(30);
        assertThat(readiness.get("purchases").get("rows").asInt()).isZero();
        assertThat(feature(readiness, "buy-incumbent").get("status").asText()).isEqualTo("locked");
        assertThat(feature(readiness, "sell-inventory").get("status").asText()).isEqualTo("locked");
        assertThat(feature(readiness, "sell-pricing").get("status").asText()).isEqualTo("ready");
        assertThat(feature(readiness, "insights-demographics").get("status").asText()).isEqualTo("partial");
    }

    @Test
    @Order(3)
    @DisplayName("a products-only tenant: costs and stock, no history, priced by the wizard")
    void productsOnlyTenant() throws Exception {
        String token = SampleTenant.signUp(mvc, json, "both", "US");
        UUID tenantId = SampleTenant.tenantIdOf(json, token);
        String csv = """
                Item No,Description,Category,Subcategory,UOM,Commodity,List Price,Unit Cost,On Hand,Branch,Supplier,Supplier Cost,Lead Time
                NP-100,1/2 IN COPPER TYPE L HARD TUBE 10FT,Plumbing,Pipe & tube,each,copper,,19.80,240,400100,Cascade Copper Mills,17.90,12
                NP-200,3/4 IN BRASS BALL VALVE,Plumbing,Valves,each,brass,,11.20,90,400100,,,
                NP-300,2 IN PVC DWV TEE,Plumbing,Fittings,each,pvc,,4.40,600,400100,,,
                NP-400,40 GAL GAS WATER HEATER,Water heating,Tank heaters,each,equipment,,505.00,6,400100,,,
                NP-500,SMART THERMOSTAT,HVAC,Controls,each,equipment,,86.00,30,400100,,,
                """;
        JsonNode committed = SampleTenant.importAndCommit(mvc, json, token, "products", csv);
        assertThat(committed.get("productsCreated").asInt()).isEqualTo(5);
        assertThat(committed.get("summary").get("pricesWritten").asInt()).isEqualTo(5);
        assertThat(committed.get("summary").get("inventoryRowsWritten").asInt()).isEqualTo(5);
        assertThat(committed.get("summary").get("suppliersCreated").asInt()).isEqualTo(1);
        assertThat(committed.get("summary").get("supplierLinksWritten").asInt()).isEqualTo(1);

        JsonNode readiness = SampleTenant.readiness(mvc, json, token);
        assertThat(readiness.get("sales").get("rows").asInt()).isZero();
        assertThat(readiness.get("prices").get("itemsMissingPrice").asInt()).isEqualTo(5);
        assertThat(readiness.get("prices").get("itemsMissingCost").asInt()).isZero();
        assertThat(readiness.get("inventory").get("items").asInt()).isEqualTo(5);
        assertThat(feature(readiness, "sell-forecast").get("status").asText()).isEqualTo("locked");
        assertThat(feature(readiness, "sell-inventory").get("status").asText()).isEqualTo("partial");
        assertThat(readiness.get("nextSteps").get(0).get("key").asText()).isEqualTo("set-prices");

        Integer priceRows = jdbc.queryForObject(
                "select count(*) from product_prices where tenant_id = ?", Integer.class, tenantId);
        assertThat(priceRows).isEqualTo(5);
        String category = jdbc.queryForObject(
                "select category from products where tenant_id = ? and item_number = 'NP-400'", String.class, tenantId);
        assertThat(category).isEqualTo("Water heating");
    }

    @Test
    @Order(4)
    @DisplayName("the wizard prices every product from cost and the benchmark band")
    void productsOnlyWizard() throws Exception {
        String token = SampleTenant.signUp(mvc, json, "both", "US");
        UUID tenantId = SampleTenant.tenantIdOf(json, token);
        SampleTenant.importAndCommit(mvc, json, token, "products",
                "Item No,Description,Category,Unit Cost\nNP-100,COPPER TUBE,Plumbing,19.80\n");

        MvcResult suggestions = mvc.perform(get("/api/v1/prices/suggestions?scope=missing")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = json.readTree(suggestions.getResponse().getContentAsString()).get("rows");
        assertThat(rows).hasSize(1);
        for (JsonNode row : rows) {
            assertThat(row.get("anchor").asText()).isEqualTo("benchmark");
            assertThat(row.get("suggestedPrice").decimalValue()).isGreaterThan(row.get("cost").decimalValue());
        }

        // The suggestion's own field names (suggestedPrice, description, category, ...) are
        // not the bulk-write request's (item, store, listPrice, cost, basis); the wizard's
        // frontend does this same mapping before it calls the endpoint.
        com.fasterxml.jackson.databind.node.ArrayNode writeRows = json.createArrayNode();
        for (JsonNode row : rows) {
            var wr = json.createObjectNode();
            wr.put("item", row.get("item").asText());
            wr.set("listPrice", row.get("suggestedPrice"));
            wr.set("cost", row.get("cost"));
            wr.set("basis", row.get("basis"));
            writeRows.add(wr);
        }
        var body = json.createObjectNode();
        body.set("rows", writeRows);
        body.put("source", "wizard");

        mvc.perform(post("/api/v1/prices/bulk")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.written").value(rows.size()));
        Integer priceRows = jdbc.queryForObject(
                "select count(*) from product_prices where tenant_id = ?", Integer.class, tenantId);
        assertThat(priceRows).isEqualTo(2 * rows.size());
    }

    @Test
    @Order(5)
    @DisplayName("rolling the custom import back leaves nothing it loaded or created")
    void rollback() throws Exception {
        mvc.perform(delete("/api/v1/imports/{id}", customBatch).header("Authorization", "Bearer " + customToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROLLED_BACK"))
                .andExpect(jsonPath("$.rolledBackAt").isNotEmpty());

        Integer rows = jdbc.queryForObject(
                "select count(*) from sales_transactions where import_batch_id = ?", Integer.class, customBatch);
        assertThat(rows).isZero();

        JsonNode readiness = SampleTenant.readiness(mvc, json, customToken);
        assertThat(readiness.get("sales").get("rows").asInt()).isZero();
        // All four products were created by the batch and nothing else references them.
        assertThat(readiness.get("catalogue").get("products").asInt()).isZero();
        assertThat(readiness.get("catalogue").get("customers").asInt()).isZero();
        Integer customers = jdbc.queryForObject(
                "select count(*) from customers where tenant_id = ?", Integer.class, customTenant);
        assertThat(customers).isZero();
    }

    private static JsonNode feature(JsonNode readiness, String key) {
        for (JsonNode feature : readiness.get("features")) {
            if (key.equals(feature.get("key").asText())) {
                return feature;
            }
        }
        throw new AssertionError("No feature " + key + " in " + readiness.get("features"));
    }

    /** The template with every {@code D-<n>} token replaced by {@code today - n}. */
    static String customCsv(LocalDate today) throws Exception {
        try (InputStream in = RealDataIT.class.getResourceAsStream("/realdata/custom-30.template.csv")) {
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
