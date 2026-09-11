package com.aatlas.bulk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.bulk.internal.BulkSellDtos.LineView;
import com.aatlas.bulk.internal.BulkSellDtos.PlanView;
import com.aatlas.bulk.internal.BulkSellDtos.ProjectionView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link BulkSellEngine#plan} against {@code golden/bulk.json}'s {@code bulkSellPlan}
 * row - the exact basket the sell/bulk page opens on (store 100959, its seven priceable
 * items). Unlike the buy side, nothing here is a stand-in for cost/price arithmetic: every
 * input {@link BulkSellEngine} reads (cost, current, recommended, inventory, monthly
 * units, elasticity) is an exact port pinned by {@link PricingEngineGoldenTest}, so this
 * test expects byte-for-byte agreement on every line and every strategy, not just shape.
 */
class BulkSellEngineGoldenTest {

    private final ObjectMapper json = new ObjectMapper();
    private final BulkSeedCatalog catalog = new BulkSeedCatalog(json);
    private final BulkPricingEngine pricing = new BulkPricingEngine(catalog);
    private final BulkSellEngine engine = new BulkSellEngine(
            new SellLineReaderImpl(pricing, catalog), new OpportunityScoring(pricing, catalog), catalog);

    private JsonNode goldenRow() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/bulk.json")) {
            assertThat(in).as("golden/bulk.json on the test classpath").isNotNull();
            JsonNode all = json.readTree(in);
            for (JsonNode row : all) {
                if ("bulkSellPlan".equals(row.get("fn").asText())) {
                    return row;
                }
            }
            throw new AssertionError("No bulkSellPlan row in golden/bulk.json");
        }
    }

    @Test
    void reproducesTheFlagshipBulkSellPlan() throws Exception {
        JsonNode row = goldenRow();
        String storeId = row.get("input").get("storeId").asText();
        List<String> items = new java.util.ArrayList<>();
        row.get("input").get("itemNumbers").forEach(n -> items.add(n.asText()));

        PlanView plan = engine.plan(storeId, items);
        JsonNode expected = row.get("output");

        assertThat(plan.storeId()).isEqualTo(expected.get("storeId").asText());
        assertThat(plan.storeLabel()).isEqualTo(expected.get("storeLabel").asText());
        assertThat(plan.totalInventoryValue()).isEqualTo(expected.get("totalInventoryValue").asDouble());
        assertThat(plan.totalOpportunity()).isEqualTo(expected.get("totalOpportunity").asDouble());
        assertThat(plan.recommendedKey()).isEqualTo(expected.get("recommendedKey").asText());

        JsonNode expectedLines = expected.get("lines");
        assertThat(plan.lines()).hasSize(expectedLines.size());
        for (int i = 0; i < plan.lines().size(); i++) {
            LineView actual = plan.lines().get(i);
            JsonNode e = expectedLines.get(i);
            String label = e.get("itemNumber").asText();
            assertThat(actual.itemNumber()).as(label).isEqualTo(e.get("itemNumber").asText());
            assertThat(actual.name()).as(label + " name").isEqualTo(e.get("name").asText());
            assertThat(actual.cost()).as(label + " cost").isEqualTo(e.get("cost").asDouble());
            assertThat(actual.current()).as(label + " current").isEqualTo(e.get("current").asDouble());
            assertThat(actual.recommended()).as(label + " recommended").isEqualTo(e.get("recommended").asDouble());
            assertThat(actual.inventoryUnits()).as(label + " inventoryUnits")
                    .isEqualTo(e.get("inventoryUnits").asDouble());
            assertThat(actual.inventoryValue()).as(label + " inventoryValue")
                    .isEqualTo(e.get("inventoryValue").asDouble());
            assertThat(actual.weeksOfCover()).as(label + " weeksOfCover").isEqualTo(e.get("weeksOfCover").asDouble());
            assertThat(actual.opportunity()).as(label + " opportunity").isEqualTo(e.get("opportunity").asDouble());
            assertThat(actual.score()).as(label + " score").isEqualTo(e.get("score").asInt());
            assertThat(actual.tier()).as(label + " tier").isEqualTo(e.get("tier").asText());
        }

        assertProjection(plan.current(), expected.get("current"));
        JsonNode expectedStrategies = expected.get("strategies");
        assertThat(plan.strategies()).hasSize(expectedStrategies.size());
        for (int i = 0; i < plan.strategies().size(); i++) {
            assertProjection(plan.strategies().get(i), expectedStrategies.get(i));
        }
    }

    private static void assertProjection(ProjectionView actual, JsonNode expected) {
        String label = expected.get("key").asText();
        assertThat(actual.key()).as(label).isEqualTo(expected.get("key").asText());
        assertThat(actual.title()).as(label + " title").isEqualTo(expected.get("title").asText());
        assertThat(actual.blurb()).as(label + " blurb").isEqualTo(expected.get("blurb").asText());
        assertThat(actual.unitsSold()).as(label + " unitsSold").isEqualTo(expected.get("unitsSold").asDouble());
        assertThat(actual.revenue()).as(label + " revenue").isEqualTo(expected.get("revenue").asDouble());
        assertThat(actual.profit()).as(label + " profit").isEqualTo(expected.get("profit").asDouble());
        assertThat(actual.marginPct()).as(label + " marginPct").isEqualTo(expected.get("marginPct").asDouble());
        assertThat(actual.turnoverPct()).as(label + " turnoverPct").isEqualTo(expected.get("turnoverPct").asDouble());
        assertThat(actual.risk()).as(label + " risk").isEqualTo(expected.get("risk").asText());

        Map<String, Double> prices = actual.prices();
        JsonNode expectedPrices = expected.get("prices");
        Iterator<String> names = expectedPrices.fieldNames();
        while (names.hasNext()) {
            String item = names.next();
            assertThat(prices).as(label + " prices key " + item).containsKey(item);
            assertThat(prices.get(item)).as(label + " price for " + item)
                    .isEqualTo(expectedPrices.get(item).asDouble());
        }
        assertThat(prices).as(label + " prices size").hasSize(expectedPrices.size());
    }
}
