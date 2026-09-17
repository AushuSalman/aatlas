package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A figure and where it came from.
 *
 * <p>Every cost and current price on every screen is one of these, so the UI can badge it:
 * {@code price-list}, {@code sales-90d}, {@code sales-12m}, {@code sales-item-12m},
 * {@code purchases-90d}, {@code sales-cost}, {@code supplier-list}.
 *
 * @param asOf the date the figure was last true on, when the source has one
 */
public record Resolved(BigDecimal value, String source, LocalDate asOf) {

    public static final String PRICE_LIST = "price-list";
    public static final String SALES_90D = "sales-90d";
    public static final String SALES_12M = "sales-12m";
    public static final String SALES_ITEM_12M = "sales-item-12m";
    public static final String PURCHASES_90D = "purchases-90d";
    public static final String SALES_COST = "sales-cost";
    public static final String SUPPLIER_LIST = "supplier-list";
    public static final String SUGGESTED = "suggested";
}
