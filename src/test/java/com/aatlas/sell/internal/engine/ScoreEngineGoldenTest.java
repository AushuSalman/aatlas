package com.aatlas.sell.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.aatlas.sell.OpportunityScoreView;
import com.aatlas.sell.internal.support.FixtureCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** Pins {@link ScoreEngine#score} against {@code golden/opportunity-score.json}: 84 rows. */
class ScoreEngineGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOL = 1.0e-6;

    private static JsonNode golden() throws Exception {
        try (InputStream in = ScoreEngineGoldenTest.class.getResourceAsStream("/golden/opportunity-score.json")) {
            assertThat(in).as("golden/opportunity-score.json on the test classpath").isNotNull();
            return JSON.readTree(in);
        }
    }

    @Test
    void reproducesEveryRow() throws Exception {
        FixtureCatalog catalog = new FixtureCatalog();
        ScoreEngine engine = new ScoreEngine(catalog, new PricingEngine(catalog));
        JsonNode rows = golden();
        assertThat(rows.size()).isEqualTo(84);

        for (JsonNode row : rows) {
            assertThat(row.get("fn").asText()).isEqualTo("opportunityScore");
            String item = row.get("input").get("itemNumber").asText();
            String storeId = row.get("input").get("storeId").asText();
            JsonNode e = row.get("output");
            String ctx = item + "@" + storeId;

            OpportunityScoreView a = engine.score(item, storeId);

            assertThat(a.score()).as(ctx + " score").isEqualTo(e.get("score").asInt());
            assertThat(a.tier()).as(ctx + " tier").isEqualTo(e.get("tier").asText());
            assertThat(a.tierLabel()).as(ctx + " tierLabel").isEqualTo(e.get("tierLabel").asText());

            JsonNode reasons = e.get("reasons");
            assertThat(a.reasons()).as(ctx + " reasons size").hasSize(reasons.size());
            for (int i = 0; i < reasons.size(); i++) {
                assertThat(a.reasons().get(i).text()).as(ctx + " reasons[" + i + "].text")
                        .isEqualTo(reasons.get(i).get("text").asText());
                assertThat(a.reasons().get(i).good()).as(ctx + " reasons[" + i + "].good")
                        .isEqualTo(reasons.get(i).get("good").asBoolean());
            }

            JsonNode signals = e.get("signals");
            assertThat(a.signals().demandLevel()).as(ctx + " signals.demandLevel")
                    .isEqualTo(signals.get("demandLevel").asText());
            assertThat(a.signals().priceGapPct().doubleValue()).as(ctx + " signals.priceGapPct")
                    .isCloseTo(signals.get("priceGapPct").asDouble(), offset(TOL));
            assertThat(a.signals().marginPct().doubleValue()).as(ctx + " signals.marginPct")
                    .isCloseTo(signals.get("marginPct").asDouble(), offset(TOL));
            assertThat(a.signals().weeksOfCover().doubleValue()).as(ctx + " signals.weeksOfCover")
                    .isCloseTo(signals.get("weeksOfCover").asDouble(), offset(TOL));
            assertThat(a.signals().conversionPct()).as(ctx + " signals.conversionPct")
                    .isEqualTo(signals.get("conversionPct").asInt());
            assertThat(a.signals().commodityPct90().doubleValue()).as(ctx + " signals.commodityPct90")
                    .isCloseTo(signals.get("commodityPct90").asDouble(), offset(TOL));
        }
    }
}
