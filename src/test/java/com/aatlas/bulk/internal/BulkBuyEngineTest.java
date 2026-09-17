package com.aatlas.bulk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.bulk.internal.BulkBuyDtos.LineView;
import com.aatlas.bulk.internal.BulkBuyDtos.PlanView;
import com.aatlas.bulk.internal.BulkBuyDtos.ProjectionView;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Structural checks for {@link BulkBuyEngine} over {@link BuyIntelFake}, a deterministic
 * fake of {@code buy.BuyIntelReader} - the real seam {@link BuyLineReaderImpl} now reads,
 * in a worktree that cannot see the real buy engine behind it. Not a golden-value test
 * (the fake's numbers are not the real buy module's, on purpose): what this test pins is
 * the shape and internal consistency {@code bulkBuyPlan}'s own logic guarantees regardless
 * of where the supplier numbers come from - five strategies in order, a valid
 * recommendation, and lowest-cost genuinely being the cheapest total.
 */
class BulkBuyEngineTest {

    private static final String REGION = "south";
    private static final List<String> ITEMS = List.of(
            "HRD304148", "HRD118902", "HRD772310", "HRD450871", "HRD290145", "HRD661204",
            "HRD983377", "HRD512066", "HRD874019", "HRD335590", "HRD107744", "HRD248813");

    private final BulkBuyEngine engine = new BulkBuyEngine(new BuyLineReaderImpl(new BuyIntelFake()));

    @Test
    void producesFiveStrategiesInOrderWithAValidRecommendation() {
        PlanView plan = engine.plan(REGION, ITEMS, 1);

        assertThat(plan.regionKey()).isEqualTo(REGION);
        assertThat(plan.regionLabel()).isEqualTo("South");
        assertThat(plan.lines()).hasSize(ITEMS.size());
        for (LineView line : plan.lines()) {
            assertThat(ITEMS).contains(line.itemNumber());
            assertThat(line.suppliers()).hasSize(8);
            assertThat(line.qty()).isPositive();
        }

        List<String> keys = plan.strategies().stream().map(ProjectionView::key).toList();
        assertThat(keys).containsExactly("lowest-cost", "fastest", "lowest-risk", "balanced", "split");
        assertThat(Set.of("lowest-cost", "balanced")).contains(plan.recommendedKey());

        ProjectionView lowestCost = plan.strategies().get(0);
        for (ProjectionView strategy : plan.strategies()) {
            assertThat(lowestCost.totalCost()).isLessThanOrEqualTo(strategy.totalCost() + 0.01);
        }

        ProjectionView split = plan.strategies().get(4);
        assertThat(split.risk()).isEqualTo("Low");
        assertThat(split.supplierCount()).isGreaterThanOrEqualTo(1);

        assertThat(plan.currentCost()).isGreaterThan(0);
        assertThat(plan.totalUnits()).isGreaterThan(0);
    }
}
