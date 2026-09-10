package com.aatlas.insights.internal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link ScoreEngine} against {@code golden/opportunity-score.json}: all 84 priceable
 * (item, store) pairs, produced by running the TypeScript's own {@code opportunityScore}.
 *
 * <p>This is this module's own port of {@code intel/score.ts} - TODO(merge): the {@code
 * sell} module's canonical {@code OpportunityScores} reader replaces this class, but both
 * are pinned against the same fixture and must agree to the cent.
 */
class ScoreEngineGoldenTest {

    @Test
    void reproducesEveryOpportunityScore() throws Exception {
        CatalogSnapshot snapshot = TestSnapshot.us();
        JsonNode golden = GoldenSupport.load("opportunity-score.json");

        int count = 0;
        var it = golden.elements();
        while (it.hasNext()) {
            JsonNode row = it.next();
            String itemNumber = row.get("input").get("itemNumber").asText();
            String storeId = row.get("input").get("storeId").asText();
            JsonNode expected = row.get("output");

            ScoreEngine.OpportunityScore actual = ScoreEngine.compute(itemNumber, storeId, snapshot);
            JsonNode actualNode = GoldenSupport.tree(actual);
            GoldenSupport.assertJsonEquals(expected, actualNode, itemNumber + "@" + storeId);
            count++;
        }
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(84);
    }
}
