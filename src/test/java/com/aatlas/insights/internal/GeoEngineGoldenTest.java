package com.aatlas.insights.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link GeoEngine} against {@code golden/geo.json}: {@code allRegions()} and
 * {@code allStores()}, produced by running the TypeScript's own {@code geo.ts}.
 *
 * <p>{@code opportunityScore} inside this engine is this module's own port (see
 * {@link ScoreEngine}); {@code buildBuyRecommendation} inside it composes wave 1's real
 * supplier and catalogue rows with this module's own pricing/logistics ports, needing no
 * stand-in (see {@link BuyEngine}).
 */
class GeoEngineGoldenTest {

    @Test
    void reproducesAllRegions() throws Exception {
        CatalogSnapshot snapshot = TestSnapshot.us();
        DealsIndex deals = new DealsIndex();
        JsonNode golden = GoldenSupport.load("geo.json").get(0);
        org.assertj.core.api.Assertions.assertThat(golden.get("fn").asText()).isEqualTo("allRegions");

        List<GeoEngine.RegionIntel> actual = GeoEngine.allRegions(snapshot, deals);
        JsonNode actualNode = GoldenSupport.tree(actual);
        GoldenSupport.assertJsonEquals(golden.get("output"), actualNode, "allRegions()");
    }

    @Test
    void reproducesAllStores() throws Exception {
        CatalogSnapshot snapshot = TestSnapshot.us();
        DealsIndex deals = new DealsIndex();
        JsonNode golden = GoldenSupport.load("geo.json").get(1);
        org.assertj.core.api.Assertions.assertThat(golden.get("fn").asText()).isEqualTo("allStores");

        List<GeoEngine.StoreIntel> actual = GeoEngine.allStores(snapshot, deals);
        JsonNode actualNode = GoldenSupport.tree(actual);
        GoldenSupport.assertJsonEquals(golden.get("output"), actualNode, "allStores()");
    }
}
