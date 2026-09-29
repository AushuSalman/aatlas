package com.aatlas.buy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.buy.MarketEvidence;
import com.aatlas.buy.MarketEvidence.Reorder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReorderEngineTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void lowStockOrdersNowBackToTheReorderPointPlusCover() {
        // 70 a week = 10 a day; 21 days' lead = 210, +50% safety = 315 reorder point; 100 on hand.
        Reorder r = ReorderEngine.plan(d("100"), TODAY, d("70"), "last 90 days", 21, "supplier", null, TODAY);
        assertThat(r.status()).isEqualTo("order-now");
        assertThat(r.reorderPoint()).isEqualByComparingTo("315");
        // 315 + 8 weeks × 70 (560) - 100 on hand = 775
        assertThat(r.orderQty()).isEqualByComparingTo("775");
        assertThat(r.orderBy()).isEqualTo(TODAY);
        assertThat(r.weeksOfCover()).isEqualByComparingTo("1.4");
        assertThat(r.summary()).startsWith("Order 775 units now");
    }

    @Test
    void comfortableStockGivesAFutureOrderByDate() {
        // 7 a week = 1 a day; 14 days assumed lead → 14 + 7 safety = 21; 60 on hand → 39 days away.
        Reorder r = ReorderEngine.plan(d("60"), TODAY, d("7"), "last 90 days", null, "supplier", null, TODAY);
        assertThat(r.leadBasis()).isEqualTo("assumed");
        assertThat(r.leadDays()).isEqualTo(ReorderEngine.DEFAULT_LEAD_DAYS);
        assertThat(r.status()).isEqualTo("ok");
        assertThat(r.orderBy()).isEqualTo(TODAY.plusDays(39));
        // Sized for that date, when stock is down to the reorder point: 8 weeks × 7 = 56.
        assertThat(r.orderQty()).isEqualByComparingTo("56");
    }

    @Test
    void orderSoonInsideTwoWeeks() {
        Reorder r = ReorderEngine.plan(d("30"), TODAY, d("7"), "b", 14, "supplier", null, TODAY);
        assertThat(r.status()).isEqualTo("order-soon");
        assertThat(r.orderBy()).isEqualTo(TODAY.plusDays(9));
    }

    @Test
    void overstockOrdersNothing() {
        Reorder r = ReorderEngine.plan(d("1000"), TODAY, d("10"), "b", 14, "supplier", null, TODAY);
        assertThat(r.status()).isEqualTo("overstocked");
        assertThat(r.orderQty()).isEqualByComparingTo("0");
    }

    @Test
    void theMinimumOrderRoundsASmallOrderUp() {
        Reorder r = ReorderEngine.plan(d("100"), TODAY, d("7"), "b", 14, "supplier", 500, TODAY);
        assertThat(r.orderQty()).isEqualByComparingTo("500");
    }

    @Test
    void noSalesOrNoStockSaySoInsteadOfGuessing() {
        assertThat(ReorderEngine.plan(d("50"), TODAY, null, "b", 14, "supplier", null, TODAY).status())
                .isEqualTo("no-sales");
        Reorder noStock = ReorderEngine.plan(null, null, d("7"), "b", 14, "supplier", null, TODAY);
        assertThat(noStock.status()).isEqualTo("no-stock");
        assertThat(noStock.orderQty()).isEqualByComparingTo("77"); // 21 reorder point + 56 cover
    }

    @Test
    void flagsPayingMoreThanRetailWorstFirst() {
        List<MarketEvidence.Flag> f = BuyRecommendationEngine.flags(d("12.00"), d("9.98"), d("6.99"), d("30"),
                d("4.00"), true, "Plumbing");
        assertThat(f).extracting(MarketEvidence.Flag::level).containsExactly("bad", "warn");
        assertThat(f.get(0).text()).contains("more than the lowest shop price");
        assertThat(f.get(1).text()).contains("Bulk lots go for");
    }

    @Test
    void flagsAboveShouldCostButUnderRetail() {
        List<MarketEvidence.Flag> f = BuyRecommendationEngine.flags(d("7.50"), d("9.98"), d("6.99"), d("30"), null,
                true, "Plumbing");
        assertThat(f).extracting(MarketEvidence.Flag::level).containsExactly("warn");
        assertThat(f.get(0).text()).contains("target margin of 30%");
    }

    @Test
    void flagsInLineAndNoEvidence() {
        assertThat(BuyRecommendationEngine.flags(d("6.20"), d("9.98"), d("6.99"), d("30"), d("6.00"), true, "Plumbing"))
                .extracting(MarketEvidence.Flag::level).containsExactly("good");
        assertThat(BuyRecommendationEngine.flags(d("6.20"), null, null, null, null, false, "Plumbing"))
                .extracting(MarketEvidence.Flag::level).containsExactly("info");
    }
}
