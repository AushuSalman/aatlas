package com.aatlas.bulk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.bulk.internal.PricingEngine.PricingModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PricingEngine#getPricingModel} against every one of the 84 rows in
 * {@code golden/pricing-model.json} - the same 84 priceable (item, store) pairs
 * {@code golden/bulk.json}'s baskets are drawn from. This is the foundation the rest of
 * bulk sell is built on: if this test is green, every price, margin floor and ceiling a
 * bulk sell line reads is byte-for-byte what the TypeScript would have produced.
 */
class PricingEngineGoldenTest {

    private final ObjectMapper json = new ObjectMapper();
    private final PricingEngine engine = new PricingEngine(new BulkSeedCatalog(json));

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/pricing-model.json")) {
            assertThat(in).as("golden/pricing-model.json on the test classpath").isNotNull();
            return json.readTree(in);
        }
    }

    @Test
    void reproducesEveryPricingModelRow() throws Exception {
        for (JsonNode row : golden()) {
            String item = row.get("item").asText();
            String storeId = row.get("storeId").asText();
            PricingModel m = engine.getPricingModel(item, storeId);

            String label = item + "@" + storeId;
            assertThat(m.priceable()).as(label + " priceable").isEqualTo(row.get("priceable").asBoolean());
            if (!m.priceable()) {
                continue;
            }
            assertThat(m.cost()).as(label + " cost").isEqualTo(row.get("cost").asDouble());
            assertThat(m.currentPrice()).as(label + " currentPrice").isEqualTo(row.get("currentPrice").asDouble());
            assertThat(m.optimalPrice()).as(label + " optimalPrice").isEqualTo(row.get("optimalPrice").asDouble());
            assertThat(m.aggressivePrice()).as(label + " aggressivePrice")
                    .isEqualTo(row.get("aggressivePrice").asDouble());
            assertThat(m.ceilingPrice()).as(label + " ceilingPrice").isEqualTo(row.get("ceilingPrice").asDouble());
            assertThat(m.peerQ1()).as(label + " peerQ1").isEqualTo(row.get("peerQ1").asDouble());
            assertThat(m.peerQ2()).as(label + " peerQ2").isEqualTo(row.get("peerQ2").asDouble());
            assertThat(m.peerQ3()).as(label + " peerQ3").isEqualTo(row.get("peerQ3").asDouble());
            JsonNode expectedMedian = row.get("competitorMedian");
            if (expectedMedian == null || expectedMedian.isNull()) {
                assertThat(m.competitorMedian()).as(label + " competitorMedian").isNull();
            } else {
                assertThat(m.competitorMedian()).as(label + " competitorMedian")
                        .isEqualTo(expectedMedian.asDouble());
            }
            assertThat(m.msaMult()).as(label + " msaMult").isEqualTo(row.get("msaMult").asDouble());
            assertThat(m.msaMode()).as(label + " msaMode").isEqualTo(row.get("msaMode").asText());
            assertThat(m.segment()).as(label + " segment").isEqualTo(row.get("segment").asText());
            assertThat(m.totalTransactions()).as(label + " totalTransactions")
                    .isEqualTo(row.get("totalTransactions").asInt());
            assertThat(m.competitorCount()).as(label + " competitorCount").isEqualTo(row.get("competitors").size());

            JsonNode expectedDemand = row.get("demand");
            if (expectedDemand == null || expectedDemand.isNull()) {
                assertThat(m.demand()).as(label + " demand").isNull();
            } else {
                assertThat(m.demand()).as(label + " demand present").isNotNull();
                assertThat(m.demand().level()).as(label + " demand.level").isEqualTo(expectedDemand.get("level").asText());
                assertThat(m.demand().movePercent()).as(label + " demand.movePercent")
                        .isEqualTo(expectedDemand.get("movePercent").asDouble());
            }
        }
    }
}
