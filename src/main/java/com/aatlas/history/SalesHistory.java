package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant's sales history, reduced.
 *
 * <p>Everything here reads {@code sales_transactions} for the tenant in {@code TenantContext}
 * through the {@code (tenant_id, product_id, store_id, txn_date)} index or the tenant prefix
 * of it plus partition pruning. Windows are supplied by the caller from {@code AatlasClock};
 * nothing in this module calls {@code now()}.
 *
 * <p>Products and stores are addressed by id. Callers resolve codes through
 * {@link Catalogue} once and pass ids down, so a text join never happens where an id exists.
 */
public interface SalesHistory {

    boolean hasHistory();

    Coverage coverage();

    SalesStats itemStore(UUID productId, UUID storeId, Window w);

    /** Every branch, and rows with no branch. */
    SalesStats item(UUID productId, Window w);

    SalesStats store(UUID storeId, Window w);

    SalesStats tenant(Window w);

    List<GroupStats> byCategory(Window w);

    /** Keyed by store code; rows with no branch fall under {@link GroupStats#NO_BRANCH}. */
    List<GroupStats> byStore(Window w);

    /** Keyed by region key; branches with no region and rows with no branch fall under {@code unassigned}. */
    List<GroupStats> byRegion(Window w);

    /** Keyed by customer segment; customers with no segment fall under {@code Unassigned}. */
    List<GroupStats> byCustomerSegment(Window w);

    /** One point per calendar month ending {@code today}'s month, gaps filled with zero rows. */
    List<MonthPoint> monthly(UUID productId, UUID storeIdOrNull, int months, LocalDate today);

    List<MonthPoint> monthlyCategory(String category, UUID storeIdOrNull, int months, LocalDate today);

    /** Priced rows across every branch. Empty when fewer than three. */
    Optional<PriceBand> priceBand(UUID productId, Window w);

    Optional<PriceBand> priceBandAtStore(UUID productId, UUID storeId, Window w);

    /** Quartiles of the other branches' medians (three or more lines each). Empty when none. */
    Optional<PeerBand> peerBand(UUID productId, UUID storeId, Window w);

    Velocity velocity(UUID productId, UUID storeIdOrNull, LocalDate today);

    /** Twelve monthly indices; empty with fewer than eighteen months of data. */
    Optional<Seasonality> seasonality(UUID productId, LocalDate today);

    /** Own-price elasticity down the ladder item-store, item, category, default. */
    Elasticity elasticity(UUID productId, UUID storeIdOrNull, LocalDate today);

    List<Mover> topMovers(int limit, LocalDate today);

    Optional<LocalDate> lastSale(UUID productId, UUID storeId);

    /** Every (product, store) pair in the window, in one query. Store id is null for rows with no branch. */
    List<PairStats> pairStats(Window w);

    /** Price band per product in the window, in one query (no buckets). */
    Map<UUID, PriceBand> priceBands(Window w);

    /** Items bought on the same orders as this one. */
    List<CoPurchase> coPurchased(UUID productId, int limit, Window w);

    // ---- records ----------------------------------------------------------------------

    /** One calendar month. {@code avgPrice}/{@code avgCost} are null when no priced/costed rows. */
    record MonthPoint(LocalDate month, BigDecimal units, BigDecimal revenue, long txns, BigDecimal avgPrice,
            BigDecimal avgCost) {
    }

    record StoreMedian(UUID storeId, String storeCode, BigDecimal median, long n, BigDecimal units) {
    }

    record Bucket(BigDecimal lo, BigDecimal hi, long n) {
    }

    /** Distribution of the prices actually charged; one invoice line is one observation. */
    record PriceBand(long n, BigDecimal min, BigDecimal q1, BigDecimal median, BigDecimal q3, BigDecimal max,
            List<StoreMedian> storeMedians, List<Bucket> buckets) {
    }

    /** What the other branches charge: quartiles of their medians. */
    record PeerBand(BigDecimal q1, BigDecimal q2, BigDecimal q3, int stores) {
    }

    /**
     * Trailing 90 days against the 90 before.
     *
     * @param trendPct null when the prior window sold nothing
     * @param volumePercentile 0-1 rank of the recent volume among the tenant's pairs; null
     *     outside the bulk model
     */
    record Velocity(BigDecimal recentPerWeek, BigDecimal priorPerWeek, BigDecimal trendPct, long recentTxns,
            long priorTxns, int historyDays, BigDecimal volumePercentile) {
    }

    /** Twelve indices, January first, mean 1. */
    record Seasonality(double[] index, int months) {
    }

    /**
     * Own-price elasticity.
     *
     * @param basis {@code item-store}, {@code item}, {@code category} or {@code default}
     * @param r2 null for the default
     * @param stdError null for the default
     */
    record Elasticity(BigDecimal coefficient, BigDecimal r2, int n, String basis, BigDecimal stdError) {

        public static final String ITEM_STORE = "item-store";
        public static final String ITEM = "item";
        public static final String CATEGORY = "category";
        public static final String DEFAULT = "default";

        public static Elasticity defaultValue() {
            return new Elasticity(new BigDecimal("-1.2"), null, 0, DEFAULT, null);
        }
    }

    record Mover(UUID productId, String itemNumber, UUID storeId, String storeCode, BigDecimal units90,
            BigDecimal unitsPrior90, BigDecimal trendPct, BigDecimal revenue12m) {
    }

    /**
     * How much history there is.
     *
     * @param months distinct calendar months with rows
     * @param noBranchRevenuePct share of revenue on rows with no branch, 0-100
     */
    record Coverage(long rows, int months, LocalDate earliest, LocalDate latest, int items, int stores,
            int customers, BigDecimal noBranchRevenuePct) {

        public static Coverage none() {
            return new Coverage(0, 0, null, null, 0, 0, 0, BigDecimal.ZERO);
        }
    }

    /** A (product, store) pair's trailing-twelve-month stats plus the velocity inputs, from one query. */
    record PairStats(UUID productId, String itemNumber, UUID storeId, String storeCode, SalesStats w12,
            BigDecimal units90, BigDecimal unitsPrior90, BigDecimal avgPrice30, BigDecimal avgPrice90p) {
    }

    /** A rollup key with its current and prior-window stats. */
    record GroupStats(String key, String label, SalesStats current, SalesStats prior) {

        public static final String NO_BRANCH = "no-branch";
        public static final String UNASSIGNED = "unassigned";
    }

    record CoPurchase(UUID productId, String itemNumber, String shortName, long orders, long base,
            BigDecimal attachPct) {
    }
}
