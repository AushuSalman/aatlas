package com.aatlas.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link OverviewEngine} against {@code golden/overview.json}'s single recorded
 * {@code getOverview()} call, produced by running the TypeScript's own
 * {@code intel/overview.ts} - field by field, rather than one whole-object comparison,
 * because two KPIs are known not to match: see the class javadoc on
 * {@link CrossTrackStandIns.ProcurementImpactReader}. Everything else - both other KPIs,
 * {@code sinceYesterday}, every opportunity, every risk, every change, every region and
 * every branch - is this module's own, real computation and is asserted exactly.
 */
class OverviewEngineGoldenTest {

    @Test
    void reproducesOverviewExceptTheBuyImpactKpis() throws Exception {
        CatalogSnapshot snapshot = TestSnapshot.us();
        DealsIndex deals = new DealsIndex();
        var decisionsReader = new CrossTrackStandIns.NoRecentDecisions();
        var procurementImpact = new CrossTrackStandIns.NoProcurementImpact();
        OverviewEngine engine = new OverviewEngine(null, deals, decisionsReader, procurementImpact);

        OverviewEngine.Overview actual = engine.compute(snapshot);
        JsonNode expected = GoldenSupport.load("overview.json").get(0).get("output");

        GoldenSupport.assertJsonEquals(expected.get("sinceYesterday"), GoldenSupport.tree(actual.sinceYesterday()), "sinceYesterday");
        GoldenSupport.assertJsonEquals(expected.get("opportunities"), GoldenSupport.tree(actual.opportunities()), "opportunities");
        GoldenSupport.assertJsonEquals(expected.get("risks"), GoldenSupport.tree(actual.risks()), "risks");
        GoldenSupport.assertJsonEquals(expected.get("changes"), GoldenSupport.tree(actual.changes()), "changes");
        GoldenSupport.assertJsonEquals(expected.get("regions"), GoldenSupport.tree(actual.regions()), "regions");
        GoldenSupport.assertJsonEquals(expected.get("stores"), GoldenSupport.tree(actual.stores()), "stores");
        assertThat(actual.decisions()).as("decisions - stood in as empty pending the decisions module").isEmpty();
        assertThat(expected.get("decisions")).as("golden was captured with an empty browser state too").isEmpty();

        // The four KPIs that do not depend on impact.buy: exact.
        JsonNode expectedKpis = expected.get("kpis");
        var actualKpis = actual.kpis();
        assertThat(actualKpis).hasSize(6);
        GoldenSupport.assertJsonEquals(expectedKpis.get(0), GoldenSupport.tree(actualKpis.get(0)), "kpis[revenue]");
        GoldenSupport.assertJsonEquals(expectedKpis.get(1), GoldenSupport.tree(actualKpis.get(1)), "kpis[margin]");
        GoldenSupport.assertJsonEquals(expectedKpis.get(3), GoldenSupport.tree(actualKpis.get(3)), "kpis[opportunity]");
        GoldenSupport.assertJsonEquals(expectedKpis.get(4), GoldenSupport.tree(actualKpis.get(4)), "kpis[inventory]");

        // kpis[savings] and kpis[adoption], and the top-level adoptionPct, read from
        // impact.buy - the buy module's procurement-ledger analytics, stood in as zero
        // (see CrossTrackStandIns). Their shape (key/label/format) still matches; their
        // value/foot/tone will not until that reader is wired in at merge.
        assertThat(actualKpis.get(2).key()).isEqualTo("savings");
        assertThat(actualKpis.get(2).label()).isEqualTo(expectedKpis.get(2).get("label").asText());
        assertThat(actualKpis.get(2).format()).isEqualTo(expectedKpis.get(2).get("format").asText());
        assertThat(actualKpis.get(5).key()).isEqualTo("adoption");
        assertThat(actualKpis.get(5).label()).isEqualTo(expectedKpis.get(5).get("label").asText());
        assertThat(actualKpis.get(5).format()).isEqualTo(expectedKpis.get(5).get("format").asText());
    }
}
