package com.aatlas.insights.internal;

import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.Catalogue;
import com.aatlas.history.PricingMath;
import com.aatlas.insights.internal.ScoreEngine.OpportunityScore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Backs {@code GET /products/scores} and {@code GET /products/{item}/scores}: one row per
 * priceable (item, branch) pair in scope, {@link ScoreEngine}'s real score plus the product's
 * name and category. Not pre-folded into "best branch per product" - the Products screen
 * builds that itself from this same flat shape.
 */
final class ProductScoresEngine {

    private ProductScoresEngine() {
    }

    /**
     * @param monthlyOpportunity null when the pair has no sales volume - an uplift with no
     *     units behind it is not a dollar figure
     * @param storeLabel the branch's legal name, or "Branch code" for one the catalogue has no
     *     row for
     */
    record ProductScoreRow(
            String itemNumber, String storeId, int score, String tier, String tierLabel,
            List<ScoreEngine.ScoreReason> reasons, ScoreEngine.Signals signals, String name, String category,
            BigDecimal currentPrice, BigDecimal optimalPrice, BigDecimal upliftPct, BigDecimal monthlyOpportunity,
            String storeLabel) {
    }

    private static ProductScoreRow row(PairFacts f, InsightsData data) {
        DealSummaries.Adoption adoption = data.adoptionFor("no-branch".equals(f.storeKey()) ? null : f.storeKey());
        OpportunityScore s = ScoreEngine.compute(f, adoption);
        Catalogue.StoreRef store = data.storesByCode().get(f.pair().storeCode());
        // Sales with no branch keep the model's own "No branch on file"; only a real code the
        // catalogue has no row for is labelled by its code.
        String storeLabel = store != null && store.legalName() != null && !store.legalName().isBlank()
                ? store.legalName()
                : f.pair().storeId() == null ? f.pair().storeLabel() : "Branch " + f.pair().storeCode();
        return new ProductScoreRow(s.itemNumber(), s.storeId(), s.score(), s.tier(), s.tierLabel(),
                s.reasons(), s.signals(), f.pair().shortName(), f.pair().category(),
                PricingMath.round2(f.currentPrice()), PricingMath.round2(f.optimalPrice()),
                PricingMath.round2(f.upliftPct()),
                f.monthlyUnits().signum() > 0 ? PricingMath.round2(f.monthlyOpportunity()) : null,
                storeLabel);
    }

    static List<ProductScoreRow> scores(String region, String filter, String sort, InsightsData data) {
        List<PairFacts> pairs = (region == null || "all".equals(region))
                ? data.pairsByKey().values().stream().toList()
                : data.pairsByKey().values().stream().filter(f -> region.equals(f.pair().regionKey())).toList();

        List<ProductScoreRow> rows = new ArrayList<>();
        for (PairFacts f : pairs) {
            if (!f.priceable()) {
                continue;
            }
            rows.add(row(f, data));
        }

        List<ProductScoreRow> filtered = (filter == null || "all".equals(filter))
                ? rows
                : rows.stream().filter(r -> r.tier().equals(filter)).toList();

        Comparator<ProductScoreRow> byScore = Comparator.comparingInt(ProductScoreRow::score).reversed();
        Comparator<ProductScoreRow> comparator = switch (sort == null ? "score" : sort) {
            case "trend" -> Comparator
                    .comparingDouble((ProductScoreRow r) -> r.signals().commodityPct90() == null ? 0
                            : Math.abs(r.signals().commodityPct90()))
                    .reversed()
                    .thenComparing(byScore);
            case "opportunity" -> Comparator
                    .comparingDouble((ProductScoreRow r) -> data.pair(r.itemNumber(), r.storeId())
                            .map(f -> Fmt.dv0(f.monthlyOpportunity())).orElse(0.0))
                    .reversed()
                    .thenComparing(byScore);
            default -> byScore;
        };
        return filtered.stream().sorted(comparator).toList();
    }

    static List<ProductScoreRow> forItem(String itemNumber, InsightsData data) {
        List<ProductScoreRow> rows = new ArrayList<>();
        for (PairFacts f : data.forItem(itemNumber)) {
            if (!f.priceable()) {
                continue;
            }
            rows.add(row(f, data));
        }
        return rows.stream().sorted(Comparator.comparingInt(ProductScoreRow::score).reversed()).toList();
    }
}
