package com.aatlas.buy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the market says an item should cost to buy, beyond the supplier panel - so a buyer gets an
 * answer on day one, and the target counts the open market, not only the suppliers on file.
 */
public final class MarketEvidence {

    private MarketEvidence() {
    }

    /** Where a point came from. */
    public static final String SUPPLIER = "supplier";
    public static final String BULK_LOTS = "bulk-lots";
    public static final String RETAIL_DERIVED = "retail-derived";

    /**
     * One comparable per-unit buying price.
     *
     * @param source {@link #SUPPLIER} (a panel quote, landed), {@link #BULK_LOTS} (open-market lots,
     *               per unit) or {@link #RETAIL_DERIVED} (competitors' shop price less the category's
     *               target margin: the cost that still earns that margin at the market price)
     */
    public record Point(String source, String label, BigDecimal value, String detail, LocalDate asOf) {
    }

    /**
     * Something the buyer should know about today's cost.
     *
     * @param level {@code bad} (overpaying), {@code warn}, {@code good} or {@code info}
     */
    public record Flag(String level, String text) {
    }

    /**
     * How many to order, and by when: stock on hand against sales speed and the supplier's lead time.
     *
     * @param status       {@code order-now}, {@code order-soon} (the order-by date is within two weeks),
     *                     {@code ok}, {@code overstocked}, {@code no-sales} or {@code no-stock}
     * @param perWeek      units sold a week, the demand the plan rests on
     * @param perWeekBasis where that came from (this branch's last 90 days, or a year of purchases)
     * @param leadDays     supplier lead time plus transit
     * @param leadBasis    {@code supplier}, {@code panel} or {@code assumed}
     * @param reorderPoint stock at which to order: lead-time demand plus safety stock
     * @param orderQty     units to order to reach the cover target (rounded up to the MOQ)
     * @param orderBy      the date stock reaches the reorder point, at today's sales speed
     * @param summary      one sentence for the screen
     */
    public record Reorder(String status, BigDecimal onHand, LocalDate onHandAsOf, BigDecimal perWeek,
            String perWeekBasis, BigDecimal weeksOfCover, Integer leadDays, String leadBasis, BigDecimal reorderPoint,
            BigDecimal orderQty, LocalDate orderBy, Integer moq, int coverWeeks, String summary,
            List<CalcStep> steps) {
    }
}
