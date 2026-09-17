package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every priceable (product, store) pair for a store or the whole tenant, with everything the
 * engines need per pair, built from a fixed handful of queries rather than one per pair.
 *
 * <p>The pair universe is the union of pairs with sales in the trailing twelve months, pairs
 * with a price-list row (a tenant-wide row applies to every active branch), and pairs with
 * stock on hand. This set is what "priceable pairs" means for Overview, product scores,
 * starters and the bulk plans. Cached per tenant under {@code CacheNames.BULK_MODEL} and
 * evicted by {@link HistoryCaches}.
 */
public interface BulkModelReader {

    BulkModel bulkModel(UUID storeIdOrNull, LocalDate today);

    /**
     * One pair.
     *
     * @param w12 trailing-twelve-month stats; {@link SalesStats#empty()} when the pair has no sales
     * @param currentPrice null when the ladder resolves nothing
     * @param cost null when the ladder resolves nothing
     * @param competitor null when no observations
     * @param onHand null when no stock count
     * @param purchases12m null when no purchase orders for the item
     */
    record PairModel(
            UUID productId, String itemNumber, String shortName, String category, String subcategory,
            String commodity,
            UUID storeId, String storeCode, String storeLabel, String regionKey, BigDecimal rpp, String segment,
            SalesStats w12, SalesStats w12prior, BigDecimal units90, BigDecimal unitsPrior90,
            BigDecimal avgPrice30, BigDecimal avgPrice90p, BigDecimal volumePercentile,
            SalesHistory.PriceBand band, SalesHistory.PeerBand peer,
            Resolved currentPrice, Resolved cost, Anchor competitor,
            Inventory.OnHand onHand, PurchaseHistory.PoStats purchases12m) {
    }

    record BulkModel(LocalDate today, List<PairModel> pairs, SalesHistory.Coverage coverage, boolean hasInventory,
            LocalDate inventoryAsOf) {
    }
}
