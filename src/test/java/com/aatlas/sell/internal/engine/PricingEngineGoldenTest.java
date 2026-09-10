package com.aatlas.sell.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.sell.internal.engine.PricingTypes.Competitor;
import com.aatlas.sell.internal.engine.PricingTypes.DemandModel;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import com.aatlas.sell.internal.support.FixtureCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Iterator;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link PricingEngine#getPricingModel} against {@code golden/pricing-model.json}: 84
 * rows, plain {@code PricingModel} objects (not {@code {fn,input,output}}), one per priceable
 * (item, store) pair. Every number is compared to the value the TypeScript {@code
 * getPricingModel} actually produced, not just "close" - see {@code golden/README.md}.
 */
class PricingEngineGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOL = 1.0e-9;

    private static JsonNode golden() throws Exception {
        try (InputStream in = PricingEngineGoldenTest.class.getResourceAsStream("/golden/pricing-model.json")) {
            assertThat(in).as("golden/pricing-model.json on the test classpath").isNotNull();
            return JSON.readTree(in);
        }
    }

    @Test
    void reproducesEveryPriceableRow() throws Exception {
        PricingEngine engine = new PricingEngine(new FixtureCatalog());
        JsonNode rows = golden();
        assertThat(rows.size()).isEqualTo(84);

        for (JsonNode row : rows) {
            String item = row.get("item").asText();
            String storeId = row.get("storeId").asText();
            PricingModel m = engine.getPricingModel(item, storeId);
            String ctx = item + "@" + storeId;

            assertThat(m.priceable()).as(ctx + " priceable").isEqualTo(row.get("priceable").asBoolean());
            assertThat(m.cost()).as(ctx + " cost").isCloseTo(row.get("cost").asDouble(), within(TOL));
            assertThat(m.currentPrice()).as(ctx + " currentPrice").isCloseTo(row.get("currentPrice").asDouble(), within(TOL));
            assertThat(m.optimalPrice()).as(ctx + " optimalPrice").isCloseTo(row.get("optimalPrice").asDouble(), within(TOL));
            assertThat(m.aggressivePrice()).as(ctx + " aggressivePrice").isCloseTo(row.get("aggressivePrice").asDouble(), within(TOL));
            assertThat(m.recommendedTier()).as(ctx + " recommendedTier").isEqualTo(row.get("recommendedTier").asText());
            assertThat(m.floorPrice()).as(ctx + " floorPrice").isCloseTo(row.get("floorPrice").asDouble(), within(TOL));
            assertThat(m.ceilingPrice()).as(ctx + " ceilingPrice").isCloseTo(row.get("ceilingPrice").asDouble(), within(TOL));
            assertThat(m.peerQ1()).as(ctx + " peerQ1").isCloseTo(row.get("peerQ1").asDouble(), within(TOL));
            assertThat(m.peerQ2()).as(ctx + " peerQ2").isCloseTo(row.get("peerQ2").asDouble(), within(TOL));
            assertThat(m.peerQ3()).as(ctx + " peerQ3").isCloseTo(row.get("peerQ3").asDouble(), within(TOL));
            assertThat(m.msaMult()).as(ctx + " msaMult").isCloseTo(row.get("msaMult").asDouble(), within(TOL));
            assertThat(m.msaMode()).as(ctx + " msaMode").isEqualTo(row.get("msaMode").asText());
            assertThat(m.segment()).as(ctx + " segment").isEqualTo(row.get("segment").asText());
            assertThat(m.totalTransactions()).as(ctx + " totalTransactions").isEqualTo(row.get("totalTransactions").asInt());
            assertThat(m.totalCompanies()).as(ctx + " totalCompanies").isEqualTo(row.get("totalCompanies").asInt());
            assertThat(m.observedMin()).as(ctx + " observedMin").isCloseTo(row.get("observedMin").asDouble(), within(TOL));
            assertThat(m.observedMax()).as(ctx + " observedMax").isCloseTo(row.get("observedMax").asDouble(), within(TOL));

            JsonNode medianNode = row.get("competitorMedian");
            if (medianNode.isNull()) {
                assertThat(m.competitorMedian()).as(ctx + " competitorMedian present").isEmpty();
            } else {
                assertThat(m.competitorMedian()).as(ctx + " competitorMedian present").isPresent();
                assertThat(m.competitorMedian().getAsDouble()).as(ctx + " competitorMedian")
                        .isCloseTo(medianNode.asDouble(), within(TOL));
            }

            JsonNode competitors = row.get("competitors");
            assertThat(m.competitors()).as(ctx + " competitor count").hasSize(competitors.size());
            for (int i = 0; i < competitors.size(); i++) {
                JsonNode c = competitors.get(i);
                Competitor actual = m.competitors().get(i);
                assertThat(actual.name()).as(ctx + " competitor[" + i + "].name").isEqualTo(c.get("name").asText());
                assertThat(actual.domain()).as(ctx + " competitor[" + i + "].domain").isEqualTo(c.get("domain").asText());
                assertThat(actual.price()).as(ctx + " competitor[" + i + "].price").isCloseTo(c.get("price").asDouble(), within(TOL));
                assertThat(actual.deltaVsCurrent()).as(ctx + " competitor[" + i + "].delta_vs_current")
                        .isCloseTo(c.get("delta_vs_current").asDouble(), within(TOL));
            }

            JsonNode demand = row.get("demand");
            if (demand.isNull()) {
                assertThat(m.demand()).as(ctx + " demand present").isNull();
            } else {
                assertThat(m.demand()).as(ctx + " demand present").isNotNull();
                DemandModel d = m.demand();
                assertThat(d.level()).as(ctx + " demand.level").isEqualTo(demand.get("level").asText());
                assertThat(d.label()).as(ctx + " demand.label").isEqualTo(demand.get("label").asText());
                assertThat(d.index()).as(ctx + " demand.index").isCloseTo(demand.get("index").asDouble(), within(TOL));
                assertThat(d.movePercent()).as(ctx + " demand.movePercent").isCloseTo(demand.get("movePercent").asDouble(), within(TOL));
                assertThat(d.confidence()).as(ctx + " demand.confidence").isEqualTo(demand.get("confidence").asText());
                assertThat(d.confWeight()).as(ctx + " demand.confWeight").isCloseTo(demand.get("confWeight").asDouble(), within(TOL));
                assertThat(d.recentVelocity()).as(ctx + " demand.recentVelocity").isCloseTo(demand.get("recentVelocity").asDouble(), within(TOL));
                assertThat(d.expectedVelocity()).as(ctx + " demand.expectedVelocity").isCloseTo(demand.get("expectedVelocity").asDouble(), within(TOL));
                assertThat(d.maxAdjustmentPct()).as(ctx + " demand.maxAdjustmentPct").isEqualTo(demand.get("maxAdjustmentPct").asInt());
                assertThat(d.trendDirection()).as(ctx + " demand.trendDirection").isEqualTo(demand.get("trendDirection").asText());
                assertThat(d.historyDays()).as(ctx + " demand.historyDays").isEqualTo(demand.get("historyDays").asInt());
            }
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double tol) {
        return org.assertj.core.data.Offset.offset(tol);
    }
}
