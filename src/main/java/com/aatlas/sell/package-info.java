/**
 * Sell recommendation reads, quotes, scenarios, ATP, apply and bulk apply.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal.
 *
 * <p>Every number here is computed on demand, synchronously, in the request thread: the
 * same deterministic arithmetic the TypeScript prototype runs (a pure function of an item,
 * a store and the {@link com.aatlas.common.seed.Seeded} hash), reading catalogue rows
 * already in Postgres from wave 1. There is no snapshot table and no worker; see
 * {@code docs/decisions.md} for why that is not a shortcut.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "sell",
        allowedDependencies = {"common", "decisions", "history"})
package com.aatlas.sell;
