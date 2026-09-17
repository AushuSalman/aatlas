package com.aatlas.bulk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aatlas.bulk.SellLine;
import com.aatlas.bulk.SellLineReader;
import com.aatlas.bulk.internal.BulkSellDtos.LineView;
import com.aatlas.bulk.internal.BulkSellDtos.PlanView;
import com.aatlas.bulk.internal.BulkSellDtos.ProjectionView;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.BulkModelReader;
import com.aatlas.history.Catalogue;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link BulkSellEngine}'s own handling of a line whose inventory is locked: {@code
 * baseUnits} falls back to {@code monthlyUnits x 3} rather than being capped at an unknown
 * (zero) {@code inventoryUnits}, every strategy's {@code turnoverPct} comes back {@code
 * null} rather than a fabricated zero, the line's own {@code opportunity} comes back
 * {@code null}, and {@code locked} carries {@code inventory} straight through - exactly
 * the "skipped, never zeroed" rule every other history-backed formula follows.
 *
 * <p>Fed over a hand-built {@link SellLine}, independent of whether {@code sell.SellLines}'
 * currently shipped implementation ({@code sell.internal.SellLinesImpl}) reports a real
 * {@code locked} yet - see {@code BulkIT}'s note on that placeholder.
 */
class BulkSellEngineTest {

    private static final String ITEM = "BLK-1";
    private static final String STORE = "100959";

    @Test
    void lockedInventoryIsNeverFabricatedIntoAZero() {
        SellLine lockedLine = new SellLine(ITEM, STORE, true, "Bulk widget", "Bulk widget", "Dallas #100959",
                "Plumbing",
                10.0, 20.0, 22.0, 24.0, 13.34, 50.0, 54.5, -1.2, 40.0,
                0.0, 0.0, 0.0, 90.0,
                "medium", 1.2, 80, "Medium",
                Map.of("cost", "purchases-90d", "currentPrice", "sales-90d"), List.of("inventory"));

        SellLineReader reader = (item, store) -> lockedLine;
        Catalogue catalogue = mock(Catalogue.class);
        when(catalogue.store(STORE)).thenReturn(Optional.empty());
        BulkModelReader bulkModelReader = mock(BulkModelReader.class);
        AatlasClock clock = AatlasClock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

        BulkSellEngine engine = new BulkSellEngine(reader, new OpportunityScoring(), bulkModelReader, catalogue,
                clock);
        PlanView plan = engine.plan(STORE, List.of(ITEM));

        assertThat(plan.lines()).hasSize(1);
        LineView line = plan.lines().get(0);
        assertThat(line.locked()).contains("inventory");
        assertThat(line.opportunity()).isNull();
        assertThat(line.inventoryAsOf()).isNull();

        for (ProjectionView strategy : plan.strategies()) {
            assertThat(strategy.turnoverPct()).isNull();
            assertThat(strategy.unitsSold()).isGreaterThan(0);
        }
    }
}
