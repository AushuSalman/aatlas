package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a slice of {@code sales_transactions} adds up to.
 *
 * <p>Price statistics run over priced rows only ({@code unit_price > 0}); margin over costed
 * rows only ({@code unit_cost IS NOT NULL}) and is never extrapolated, which is why
 * {@code costedUnits}/{@code costedRevenue} ride along. A null money field means "no rows
 * qualified", never zero.
 *
 * @param txns invoice lines
 * @param units quantity, all rows
 * @param revenue {@code sum(qty * unit_price)}, all rows
 * @param cogs {@code sum(qty * unit_cost)} over costed rows; null when none
 * @param customers distinct customer keys (id, else lower-cased code)
 * @param avgPrice quantity-weighted average price over priced rows
 * @param lastPrice quantity-weighted price over the trailing 90 days, else the window average
 * @param lastPriceSource {@code sales-90d} or {@code sales-12m}; null when no priced rows
 * @param grossMarginPct {@code (costedRevenue - cogs) / costedRevenue * 100}; null when no costed rows
 * @param belowCostTxns lines sold under their own cost
 * @param zeroPriceTxns free-of-charge lines, kept in units and excluded from price statistics
 */
public record SalesStats(
        long txns,
        BigDecimal units,
        BigDecimal revenue,
        BigDecimal cogs,
        BigDecimal costedUnits,
        BigDecimal costedRevenue,
        int customers,
        BigDecimal avgPrice,
        BigDecimal lastPrice,
        String lastPriceSource,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        BigDecimal avgCost,
        BigDecimal lastCost,
        String lastCostSource,
        BigDecimal grossMarginPct,
        long belowCostTxns,
        long zeroPriceTxns,
        LocalDate firstSale,
        LocalDate lastSale) {

    public static SalesStats empty() {
        return new SalesStats(0, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, 0,
                null, null, null, null, null, null, null, null, null, 0, 0, null, null);
    }

    /** Whether any row landed in the slice. */
    public boolean any() {
        return txns > 0;
    }

    /** Share of units that carried a cost, 0-100; what a margin figure is "on". */
    public BigDecimal costCoveragePct() {
        if (units == null || units.signum() == 0 || costedUnits == null) {
            return BigDecimal.ZERO;
        }
        return costedUnits.multiply(BigDecimal.valueOf(100)).divide(units, 1, java.math.RoundingMode.HALF_UP);
    }
}
