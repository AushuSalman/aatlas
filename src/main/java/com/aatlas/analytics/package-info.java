/**
 * The procurement ledger: ~800 seeded purchase-order lines over 26 months, and the analytics
 * the Buying insights dashboard reduces from them at read time (spend, savings, capture rate,
 * supplier scorecard, delivery record, mix breakdowns, opportunities).
 *
 * <p>Application module. Types in this package root are the public API other modules may
 * depend on; everything under {@code internal} is implementation.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "analytics",
        allowedDependencies = {"common", "history", "ingest"})
package com.aatlas.analytics;
