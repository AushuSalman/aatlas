package com.aatlas.insights.internal;

import com.aatlas.insights.internal.ScoreEngine.OpportunityScore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Backs {@code GET /products/scores} and {@code GET /products/{item}/scores}.
 *
 * <p>The frontend's own typed API client ({@code src/lib/platform/backend.ts}'s
 * {@code productsApi}) already names the wire shape: both endpoints return a flat list of
 * {@code ProductScoreRow} - {@link ScoreEngine.OpportunityScore} plus the product's
 * {@code name} and {@code category} - one row per priceable (item, branch) pair in scope,
 * not a pre-aggregated "best branch per product" summary. The Products screen builds that
 * aggregation (best branch, average score, strong count, total opportunity) itself from
 * this same flat shape today by calling {@code opportunityScore} directly per (item,
 * store); wiring it to the API is meant to hand it the identical rows to fold the same way,
 * so this engine does not pre-fold them.
 */
final class ProductScoresEngine {

    private ProductScoresEngine() {
    }

    record ProductScoreRow(
            String itemNumber, String storeId, int score, String tier, String tierLabel,
            List<ScoreEngine.ScoreReason> reasons, ScoreEngine.Signals signals, String name, String category) {
    }

    private static ProductScoreRow row(String itemNumber, String storeId, CatalogSnapshot snapshot) {
        OpportunityScore s = ScoreEngine.compute(itemNumber, storeId, snapshot);
        ProductRef meta = snapshot.product(itemNumber).orElseThrow();
        return new ProductScoreRow(s.itemNumber(), s.storeId(), s.score(), s.tier(), s.tierLabel(),
                s.reasons(), s.signals(), meta.shortName(), meta.category());
    }

    /** {@code GET /products/scores}: one row per priceable (item, branch) pair in scope. */
    static List<ProductScoreRow> scores(String region, String filter, String sort, CatalogSnapshot snapshot) {
        List<StoreRef> stores = (region == null || "all".equals(region))
                ? snapshot.allStoresOrdered()
                : snapshot.allStoresOrdered().stream()
                        .filter(s -> snapshot.marketRegionForState(s.state()).key().equals(region))
                        .toList();

        List<ProductScoreRow> rows = new ArrayList<>();
        for (ProductRef p : snapshot.sellableProducts()) {
            for (StoreRef store : stores) {
                if (!snapshot.priceable(p.itemNumber(), store.storeCode())) {
                    continue;
                }
                rows.add(row(p.itemNumber(), store.storeCode(), snapshot));
            }
        }

        List<ProductScoreRow> filtered = (filter == null || "all".equals(filter))
                ? rows
                : rows.stream().filter(r -> r.tier().equals(filter)).toList();

        Comparator<ProductScoreRow> byScore = Comparator.comparingInt(ProductScoreRow::score).reversed();
        Comparator<ProductScoreRow> comparator = switch (sort == null ? "score" : sort) {
            case "trend" -> Comparator
                    .comparingDouble((ProductScoreRow r) -> Math.abs(snapshot.commodity(
                            snapshot.product(r.itemNumber()).map(ProductRef::commodity).orElse("none")).pct90AsDouble()))
                    .reversed()
                    .thenComparing(byScore);
            case "opportunity" -> Comparator
                    .comparingDouble((ProductScoreRow r) -> SellEngine.compute(r.itemNumber(), r.storeId(), snapshot).monthlyOpportunity())
                    .reversed()
                    .thenComparing(byScore);
            default -> byScore;
        };
        return filtered.stream().sorted(comparator).toList();
    }

    /** {@code GET /products/{item}/scores}: every branch, unscoped, best score first. */
    static List<ProductScoreRow> forItem(String itemNumber, CatalogSnapshot snapshot) {
        List<ProductScoreRow> rows = new ArrayList<>();
        for (StoreRef store : snapshot.allStoresOrdered()) {
            if (!snapshot.priceable(itemNumber, store.storeCode())) {
                continue;
            }
            rows.add(row(itemNumber, store.storeCode(), snapshot));
        }
        return rows.stream().sorted(Comparator.comparingInt(ProductScoreRow::score).reversed()).toList();
    }
}
