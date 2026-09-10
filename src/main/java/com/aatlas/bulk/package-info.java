/**
 * Bulk sell and bulk buy: many products, one decision.
 *
 * <p>Bulk sell prices a basket at one store three ways (max profit, fast movement,
 * balanced); bulk buy awards a basket into one region five ways (lowest cost, fastest,
 * lowest risk, balanced, split). Both are ports of {@code src/lib/intel/bulk.ts}.
 *
 * <p>Both strategies are pure functions of a basket plus a store or region, but they call
 * the same per-item recommendation logic the Sell and Buy engines compute. Those engines
 * live in the {@code sell} and {@code buy} modules, which this module's worktree does not
 * see. {@link SellLineReader} and {@link BuyLineReader} are this module's own small,
 * honest ports of just enough of {@code getSellIntel} and {@code getBuyIntel} to run the
 * bulk strategies; {@link DecisionRecorder} is the same kind of stand-in for the
 * {@code decisions} module's recorder. Every one of the three is named
 * {@code TODO(merge)} at its implementation for the lead to retarget - see the module's
 * report for exactly what each stands in for.
 *
 * <p>Application module. Types in this package root are the public API other modules may
 * depend on; everything under it is internal.
 */
@org.springframework.modulith.ApplicationModule(displayName = "bulk")
package com.aatlas.bulk;
