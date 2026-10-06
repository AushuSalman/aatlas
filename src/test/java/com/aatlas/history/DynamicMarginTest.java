package com.aatlas.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DynamicMarginTest {

    private static final Reference.Benchmark ELECTRONICS_BENCH = new Reference.Benchmark("Electronics", null,
            new BigDecimal("30"), new BigDecimal("22"), new BigDecimal("38"), null, "Electronics");
    private static final UUID ITEM = UUID.randomUUID();

    private static MarginProfiles.ItemMargin item(UUID id, String category, long lines, String price, String margin,
            String low) {
        return new MarginProfiles.ItemMargin(id, category, lines, new BigDecimal(price), new BigDecimal(margin),
                low == null ? null : new BigDecimal(low));
    }

    private static MarginProfiles.Profile profile(List<MarginProfiles.ItemMargin> items) {
        return new MarginProfiles.Profile(LocalDate.of(2026, 10, 6), items);
    }

    /** A thin-margin category the tenant sells a lot of: 4-5% margins on ~$200 items. */
    private static List<MarginProfiles.ItemMargin> electronics() {
        List<MarginProfiles.ItemMargin> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(item(UUID.randomUUID(), "Electronics", 60, "200", i % 2 == 0 ? "4" : "5", "3"));
        }
        return rows;
    }

    @Test
    void aCategoryThatEarnsFourToFivePercentIsNotPricedAtTheThirtyPercentBenchmark() {
        DynamicMargin.Target t = DynamicMargin.learn(profile(electronics()), UUID.randomUUID(), "Electronics",
                new BigDecimal("200"), ELECTRONICS_BENCH, 6, true, 0.8, 6);

        // 600 lines of real 4-5% margins far outweigh the 30% benchmark.
        assertThat(t.targetPct().doubleValue()).isBetween(4.0, 6.5);
        assertThat(t.floorPct().doubleValue()).isLessThan(t.targetPct().doubleValue());
        assertThat(t.basis()).contains("600 sales in Electronics");
    }

    @Test
    void aNewItemStartsBelowItsCategoryAndWorksUpAsPricesAreApplied() {
        DynamicMargin.Target first = DynamicMargin.learn(profile(electronics()), UUID.randomUUID(), "Electronics",
                new BigDecimal("200"), ELECTRONICS_BENCH, 0, true, 0.8, 6);
        DynamicMargin.Target half = DynamicMargin.learn(profile(electronics()), UUID.randomUUID(), "Electronics",
                new BigDecimal("200"), ELECTRONICS_BENCH, 3, true, 0.8, 6);
        DynamicMargin.Target full = DynamicMargin.learn(profile(electronics()), UUID.randomUUID(), "Electronics",
                new BigDecimal("200"), ELECTRONICS_BENCH, 6, true, 0.8, 6);

        assertThat(first.targetPct().doubleValue()).isCloseTo(full.targetPct().doubleValue() * 0.8,
                org.assertj.core.data.Offset.offset(0.05));
        assertThat(half.targetPct()).isBetween(first.targetPct(), full.targetPct());
        assertThat(first.note()).contains("starts at 80%");
    }

    @Test
    void anItemWithItsOwnHistoryLeansOnItsOwnMargin() {
        List<MarginProfiles.ItemMargin> rows = electronics();
        rows.add(item(ITEM, "Electronics", 120, "200", "9", "7"));

        DynamicMargin.Target t = DynamicMargin.learn(profile(rows), ITEM, "Electronics", new BigDecimal("200"),
                ELECTRONICS_BENCH, 0, true, 0.8, 6);

        // 120 own lines at 9% against a ~4.6% category: mostly its own, and not ramped (it is not new).
        assertThat(t.targetPct().doubleValue()).isBetween(8.0, 9.0);
        // With 20+ own lines the floor is its own low end.
        assertThat(t.floorPct()).isEqualByComparingTo("7");
    }

    @Test
    void pricierItemsCarryALowerPercentageThanCheapOnes() {
        // Catalogue where margin falls as price rises: cheap 40%, mid 30%, dear 20%.
        List<MarginProfiles.ItemMargin> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(item(UUID.randomUUID(), "Tools", 40, "5", "40", null));
            rows.add(item(UUID.randomUUID(), "Tools", 40, "20", "30", null));
            rows.add(item(UUID.randomUUID(), "Tools", 40, "80", "20", null));
        }
        DynamicMargin.Target cheap = DynamicMargin.learn(profile(rows), UUID.randomUUID(), "Tools",
                new BigDecimal("5"), null, 6, true, 0.8, 6);
        DynamicMargin.Target dear = DynamicMargin.learn(profile(rows), UUID.randomUUID(), "Tools",
                new BigDecimal("80"), null, 6, true, 0.8, 6);
        DynamicMargin.Target flat = DynamicMargin.learn(profile(rows), UUID.randomUUID(), "Tools",
                new BigDecimal("80"), null, 6, false, 0.8, 6);

        assertThat(DynamicMargin.slope(rows)).isCloseTo(-5, org.assertj.core.data.Offset.offset(0.1));
        assertThat(cheap.targetPct()).isGreaterThan(dear.targetPct());
        assertThat(dear.targetPct()).isLessThan(flat.targetPct());
        assertThat(dear.note()).contains("pricier item");
    }

    @Test
    void noSalesAtAllStartsFromTheBenchmark() {
        DynamicMargin.Target t = DynamicMargin.learn(MarginProfiles.Profile.empty(LocalDate.of(2026, 10, 6)), ITEM,
                "Electronics", new BigDecimal("100"), ELECTRONICS_BENCH, 6, true, 0.8, 6);

        assertThat(t.targetPct()).isEqualByComparingTo("30");
        assertThat(t.floorPct()).isEqualByComparingTo("22");
        assertThat(t.basis()).contains("industry benchmark");
    }
}
