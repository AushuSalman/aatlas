package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tenant's purchase history ({@code purchase_order}), reduced.
 *
 * <p>Suppliers are addressed by {@code supplier_key} (the wire id) and items by
 * {@code item_number}, which the purchases import writes as the canonical
 * {@code products.item_number}, so the text joins here are exact.
 */
public interface PurchaseHistory {

    boolean hasHistory();

    PurchaseCoverage coverage();

    PoStats itemSupplier(String itemNumber, String supplierKey, Window w);

    ItemPurchases item(String itemNumber, Window w);

    /**
     * The item's lines shipped to one branch, any supplier. The cost ladder's purchases rung
     * reads the branch first and falls back to every branch; a default so an implementation
     * that has no branch dimension still answers.
     */
    default PoStats itemAtStore(String itemNumber, java.util.UUID storeId, Window w) {
        return item(itemNumber, w).all();
    }

    /** The supplier with the largest share of the item's spend in the trailing twelve months, else the last order's. */
    Optional<SupplierShare> incumbent(String itemNumber, LocalDate today);

    SupplierPurchases supplier(String supplierKey, LocalDate today);

    List<SupplierPurchases> bySupplier(Window w);

    List<PoGroup> byCategory(Window w);

    /** Keyed by branch code; lines with no branch fall under {@code no-branch}. */
    List<PoGroup> byBranch(Window w);

    /** Keyed by supplier country. */
    List<PoGroup> byOrigin(Window w);

    List<PoMonth> monthly(String itemNumberOrNull, String supplierKeyOrNull, int months, LocalDate today);

    /** Every item's stats in the window, keyed by item number, in one query. */
    Map<String, PoStats> itemStats(Window w);

    BigDecimal savedTotal(Window w);

    BigDecimal overpaidTotal(Window w);

    // ---- records ----------------------------------------------------------------------

    /**
     * What a slice of the ledger adds up to.
     *
     * @param received lines with a received date
     * @param otifMeasurable lines with a promised and a received date
     * @param otifPct on-time-in-full over measurable lines; null when none
     * @param avgLeadDays mean order-to-dock days over received lines; null when none
     * @param inFullPct received lines whose received quantity met the order; null when none received
     * @param overpaid {@code sum(max(0, landed - baseline) * qty)}
     */
    record PoStats(long pos, long received, BigDecimal units, BigDecimal spend, BigDecimal avgLanded,
            BigDecimal avgExWorks, BigDecimal lastLanded, BigDecimal lastExWorks, LocalDate firstOrder,
            LocalDate lastOrder, BigDecimal avgLeadDays, BigDecimal leadSdDays, long otifMeasurable,
            BigDecimal otifPct, BigDecimal inFullPct, BigDecimal saved, BigDecimal overpaid, BigDecimal leaked,
            BigDecimal baselineSpend) {

        public static PoStats empty() {
            return new PoStats(0, 0, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null, null, null,
                    null, 0, null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        public boolean any() {
            return pos > 0;
        }
    }

    /** One supplier's slice of an item, with its share of the item's spend (0-100). */
    record SupplierShare(String supplierKey, String name, String country, BigDecimal sharePct, PoStats stats) {
    }

    /** An item across suppliers, spend descending. */
    record ItemPurchases(String itemNumber, PoStats all, List<SupplierShare> suppliers) {
    }

    /** One month's on-time rate over received lines; null when nothing was received. */
    record MonthOtif(LocalDate month, BigDecimal otifPct, long received) {
    }

    /**
     * A supplier's record with the tenant.
     *
     * @param otifTrend the last six months, oldest first
     */
    record SupplierPurchases(String supplierKey, PoStats w12, PoStats allTime, BigDecimal shareOfTenantSpendPct,
            List<MonthOtif> otifTrend) {
    }

    record PoMonth(LocalDate month, BigDecimal spend, BigDecimal units, long pos, BigDecimal avgLanded,
            BigDecimal saved, BigDecimal leaked) {
    }

    record PoGroup(String key, String label, PoStats current, PoStats prior) {
    }

    record PurchaseCoverage(long rows, int months, LocalDate earliest, LocalDate latest, int items, int suppliers,
            int branches, long received) {

        public static PurchaseCoverage none() {
            return new PurchaseCoverage(0, 0, null, null, 0, 0, 0, 0);
        }
    }
}
