package com.aatlas.sell.internal.buy;

import java.util.List;

/**
 * Stand-in for what {@code allocateInventory} in {@code sell2.ts} actually reads from the
 * buy side: {@code getBuyIntel(...).incumbent} and {@code .suppliers}, which come from
 * {@code buildBuyRecommendation} in {@code platform/api.ts} - landed cost per supplier
 * across freight, duty and commercial terms. That is Track Buy's engine (a whole other
 * migration and module), not in this worktree.
 *
 * <p>{@code GET /sell/atp} still needs a supplier panel and an incumbent to allocate stock
 * against, so this reads the tenant's seeded supplier panel directly (the same {@code
 * suppliers} table wave 1's {@code suppliers} module owns, real rows, {@code is_custom =
 * false} to mirror the frontend's {@code SUPPLIERS} constant) and works out the incumbent
 * the same way {@code currentSupplierFor(item)} in {@code platform/data.ts} does: rank by
 * price index, split the panel in half, seed-pick from the cheaper or the dearer half.
 * "Next-best" lots are approximated by on-time percent rather than the full landed +
 * commercial-terms "effective cost" {@code evaluate()} computes in {@code buy.ts}, since
 * that needs the freight/duty lanes and terms engine Buy owns.
 *
 * <p>TODO(merge): replace with Track Buy's {@code getBuyIntel} (or a public reader it
 * exposes) once that module exists in this tree; {@code allocateInventory}'s golden rows
 * are not reproduced bit-for-bit by this stand-in for exactly that reason - see the report.
 */
public interface BuySupplierGateway {

    /** The seeded panel (not custom/looked-up suppliers), in no particular order. */
    List<SupplierRef> seededPanel();
}
