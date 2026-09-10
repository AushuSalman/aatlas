package com.aatlas.insights.internal;

import com.aatlas.common.seed.Seeded;
import com.aatlas.insights.internal.PricingEngine.PricingModel;
import com.aatlas.insights.internal.SellEngine.Inventory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A straightforward port of {@code intel/score.ts}'s {@code opportunityScore}: one number
 * per (item, store), the same five signals, in the frontend's own weighting.
 *
 * <p>TODO(merge): {@code opportunityScore} is canonically owned by the {@code sell} module
 * (a different track, a different worktree). Products needs to list/rank/filter by it, and
 * Overview's "raise prices" opportunity and Sell's chip read it too. This is this module's
 * own copy so Products, Overview and Stores can ship without waiting on that track - at
 * merge, replace this class with the {@code sell} module's {@code OpportunityScores}
 * reader. Both are pinned by the same {@code golden/opportunity-score.json} and must agree.
 */
final class ScoreEngine {

    private ScoreEngine() {
    }

    record ScoreReason(String text, boolean good) {
    }

    record Signals(
            String demandLevel, double priceGapPct, double marginPct, double weeksOfCover,
            int conversionPct, double commodityPct90) {
    }

    record OpportunityScore(
            String itemNumber, String storeId, int score, String tier, String tierLabel,
            List<ScoreReason> reasons, Signals signals) {
    }

    static String tierLabel(String tier) {
        return switch (tier) {
            case "strong" -> "Strong opportunity";
            case "watch" -> "Watch";
            default -> "Risk";
        };
    }

    static OpportunityScore compute(String itemNumber, String storeId, CatalogSnapshot snapshot) {
        PricingModel m = PricingEngine.compute(itemNumber, storeId, snapshot);
        String key = "score:" + itemNumber + ":" + storeId;
        ProductRef meta = snapshot.product(itemNumber)
                .orElseThrow(() -> new IllegalStateException("Unknown item " + itemNumber));

        if (!m.priceable()) {
            return new OpportunityScore(itemNumber, storeId, 0, "risk", "No history",
                    List.of(new ScoreReason("No sales history at this store", false)),
                    new Signals("none", 0, 0, 0, 0, 0));
        }

        List<ScoreReason> reasons = new ArrayList<>();
        double score = 42;

        String demandLevel = m.demand() == null ? "none" : m.demand().level();
        if (demandLevel.equals("high")) {
            score += 16;
            reasons.add(new ScoreReason("Demand rising", true));
        } else if (demandLevel.equals("medium")) {
            score += 5;
            reasons.add(new ScoreReason("Demand steady", true));
        } else if (demandLevel.equals("low")) {
            score -= 14;
            reasons.add(new ScoreReason("Demand falling", false));
        }

        double market = m.competitorMedian() != null ? m.competitorMedian() : m.peerQ2();
        double priceGapPct = Fmt.round1(((market - m.currentPrice()) / m.currentPrice()) * 100);
        if (priceGapPct > 3) {
            score += 16;
            reasons.add(new ScoreReason("Your price " + Fmt.toFixed(priceGapPct, 1) + "% below market", true));
        } else if (priceGapPct >= 0) {
            score += 5;
            reasons.add(new ScoreReason("Priced at market", true));
        } else if (priceGapPct > -6) {
            score -= 8;
            reasons.add(new ScoreReason("Priced " + Fmt.toFixed(Math.abs(priceGapPct), 1) + "% above market", false));
        } else {
            score -= 16;
            reasons.add(new ScoreReason("Priced " + Fmt.toFixed(Math.abs(priceGapPct), 1) + "% above market", false));
        }

        double commodityPct90 = snapshot.commodity(meta.commodity()).pct90AsDouble();
        if (commodityPct90 >= 2) {
            score += 6;
            reasons.add(new ScoreReason("Market price rising", true));
        } else if (commodityPct90 <= -1.5) {
            score -= 5;
            reasons.add(new ScoreReason("Market price softening", false));
        }

        double marginPct = PricingEngine.marginPercent(m.currentPrice(), m.cost());
        if (marginPct >= 30) {
            score += 5;
            reasons.add(new ScoreReason("Healthy margin", true));
        } else if (marginPct < 22) {
            score -= 9;
            reasons.add(new ScoreReason("Thin margin (" + Fmt.toFixed(marginPct, 0) + "%)", false));
        }

        int units = SellEngine.monthlyUnitsFor(itemNumber, storeId, m.cost());
        Inventory inv = SellEngine.inventoryFor(itemNumber, storeId, units);
        double weeksOfCover = inv.weeksOfCover();
        if (weeksOfCover >= 4 && weeksOfCover <= 12) {
            score += 7;
            reasons.add(new ScoreReason("Healthy inventory", true));
        } else if (weeksOfCover > 16) {
            score -= 10;
            reasons.add(new ScoreReason("Overstocked (" + Fmt.toFixed(weeksOfCover, 0) + " weeks of cover)", false));
        } else if (weeksOfCover < 3) {
            score -= 5;
            reasons.add(new ScoreReason("Low stock", false));
        }

        int conversionPct = (int) Math.round(Seeded.randRange(key, "conv", 44, 93));
        if (conversionPct >= 75) {
            score += 6;
            reasons.add(new ScoreReason("High conversion", true));
        } else if (conversionPct < 55) {
            score -= 7;
            reasons.add(new ScoreReason("Low conversion", false));
        }

        int finalScore = (int) Math.max(5, Math.min(97, Math.round(score)));
        String tier = finalScore >= 75 ? "strong" : finalScore >= 45 ? "watch" : "risk";

        // Good reasons first (stable sort keeps declaration order within each group), so the
        // top of the list explains the number's direction; risk tiers read bad-first instead.
        List<ScoreReason> sorted = new ArrayList<>(reasons);
        sorted.sort((a, b) -> Boolean.compare(!a.good(), !b.good()));
        if (tier.equals("risk")) {
            Collections.reverse(sorted);
        }
        List<ScoreReason> top5 = sorted.size() > 5 ? sorted.subList(0, 5) : sorted;

        return new OpportunityScore(itemNumber, storeId, finalScore, tier, tierLabel(tier), List.copyOf(top5),
                new Signals(demandLevel, priceGapPct, Fmt.round1(marginPct), weeksOfCover, conversionPct, commodityPct90));
    }
}
