package com.aatlas.insights.internal;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link DemographicsEngine} against {@code golden/demographics.json}: the seven
 * recorded {@code getDemographics} calls (the Insights page's default filter, then one
 * field changed at a time), produced by running the TypeScript's own
 * {@code intel/demographics.ts}.
 */
class DemographicsEngineGoldenTest {

    @Test
    void reproducesEveryRecordedFilter() throws Exception {
        CatalogSnapshot snapshot = TestSnapshot.us();
        DealsIndex deals = new DealsIndex();
        JsonNode golden = GoldenSupport.load("demographics.json");

        int count = 0;
        var it = golden.elements();
        while (it.hasNext()) {
            JsonNode row = it.next();
            JsonNode f = row.get("input").get("f");
            DemographicsEngine.Filter filter = new DemographicsEngine.Filter(
                    f.get("region").asText(),
                    f.get("state").asText(),
                    f.get("storeId").isNull() ? null : f.get("storeId").asText(),
                    f.get("segment").asText(),
                    f.get("category").asText(),
                    f.get("period").asText());

            DemographicsEngine.Demographics actual = DemographicsEngine.compute(filter, snapshot, deals);
            JsonNode actualNode = GoldenSupport.tree(actual);
            GoldenSupport.assertJsonEquals(row.get("output"), actualNode, "getDemographics(" + f + ")");
            count++;
        }
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(7);
    }
}
