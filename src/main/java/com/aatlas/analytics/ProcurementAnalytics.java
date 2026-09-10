package com.aatlas.analytics;

/**
 * The one figure another module needs out of the procurement ledger: the buy-side impact
 * summary {@code decisions}' {@code buildImpact} port folds in alongside the sell-side figures
 * reduced from {@code deal}. Everything else the Buying insights dashboard shows is reached
 * through the {@code /analytics/procurement/*} HTTP endpoints, not through this interface.
 */
public interface ProcurementAnalytics {

    /** The signed-in tenant's trailing-twelve-month buy impact, across every branch and category. */
    BuyImpactSummary trailingTwelveMonthImpact();
}
