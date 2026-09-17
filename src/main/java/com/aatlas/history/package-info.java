/**
 * The real read layer: what the tenant's own rows say.
 *
 * <p>Sales history ({@code sales_transactions}), purchase history ({@code purchase_order}),
 * the price list ({@code product_prices}), stock on hand ({@code inventory_positions}),
 * competitor observations ({@code competitor_prices}), the catalogue and supplier panel
 * they key on, and the reference tables (benchmarks, commodities, lanes, guardrails) - read
 * through JDBC and reduced into the records in this package. Every engine's figure comes
 * from here or is labelled as a reference estimate; nothing in this module hashes a code.
 *
 * <p>Application module. Types in this package root are the public API other modules may
 * depend on; everything under {@code internal} is implementation. Depends on {@code common}
 * only, so every business module may depend on it without a cycle.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "history",
        allowedDependencies = {"common"})
package com.aatlas.history;
