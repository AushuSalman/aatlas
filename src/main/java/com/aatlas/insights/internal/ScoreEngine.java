package com.aatlas.insights.internal;

import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.PricingMath;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Spec 3.3's 0-100 opportunity score, over {@code PricingMath.score} - the same formula and
 * the same real inputs (demand, price gap to the real anchor, commodity trend, margin on
 * costed rows, weeks of cover, decision follow-rate) that {@code sell} scores a pair with, so
 * a product reads the same tier everywhere. {@code signals.conversionPct} keeps its wire name
 * but now means follow-rate: null without any recorded decisions.
 */
final class ScoreEngine {

    private ScoreEngine() {
    }

    record ScoreReason(String text, boolean good) {
    }

    record Signals(
            String demandLevel, Double priceGapPct, Double marginPct, Double weeksOfCover,
            Integer conversionPct, Double commodityPct90) {
    }

    record OpportunityScore(
            String itemNumber, String storeId, int score, String tier, String tierLabel,
            List<ScoreReason> reasons, Signals signals) {
    }

    static String tierLabel(String tier) {
        return switch (tier) {
            case PricingMath.Score.STRONG -> "Strong opportunity";
            case PricingMath.Score.WATCH -> "Watch";
            default -> "Risk";
        };
    }

    static OpportunityScore compute(PairFacts f, DealSummaries.Adoption adoption) {
        if (!f.priceable()) {
            return new OpportunityScore(f.pair().itemNumber(), f.storeKey(), 5, PricingMath.Score.RISK, "Risk",
                    List.of(new ScoreReason("No price on file for this branch", false)),
                    new Signals(f.demand() == null ? null : f.demand().level(), null, null, null, null,
                            Fmt.dv(f.commodityPct90())));
        }
        BigDecimal followRatePct = adoption.followRatePct().map(BigDecimal::valueOf).orElse(null);
        PricingMath.Score score = f.score(followRatePct, adoption.total());
        List<ScoreReason> reasons = reasonsFor(f, score);
        Signals signals = new Signals(
                f.demand() == null ? null : f.demand().level(),
                Fmt.dv(score.priceGapPct()),
                Fmt.dv(f.marginPct()),
                f.hasInventory() ? Fmt.dv(f.weeksOfCover()) : null,
                followRatePct == null ? null : (int) Math.round(followRatePct.doubleValue()),
                Fmt.dv(f.commodityPct90()));
        return new OpportunityScore(f.pair().itemNumber(), f.storeKey(), score.score(), score.tier(),
                tierLabel(score.tier()), reasons, signals);
    }

    /** Real reasons behind a score: one per part that actually moved it, good news first (worst first on risk tiers). */
    static List<ScoreReason> reasonsFor(PairFacts f, PricingMath.Score s) {
        List<ScoreReason> reasons = new ArrayList<>();
        if (s.demand() > 0) {
            reasons.add(new ScoreReason(s.demand() >= 16 ? "Demand rising" : "Demand steady", true));
        } else if (s.demand() < 0) {
            reasons.add(new ScoreReason("Demand falling", false));
        }
        if (s.priceGapPct() != null) {
            double g = s.priceGapPct().doubleValue();
            if (s.priceGap() > 0) {
                reasons.add(new ScoreReason("Your price " + Fmt.toFixed(g, 1) + "% below market", true));
            } else if (s.priceGap() == 0 && g >= 0) {
                reasons.add(new ScoreReason("Priced at market", true));
            } else if (s.priceGap() < 0) {
                reasons.add(new ScoreReason("Priced " + Fmt.toFixed(Math.abs(g), 1) + "% above market", false));
            }
        }
        if (s.commodity() > 0) {
            reasons.add(new ScoreReason("Market price rising", true));
        } else if (s.commodity() < 0) {
            reasons.add(new ScoreReason("Market price softening", false));
        }
        if (s.margin() > 0) {
            reasons.add(new ScoreReason("Healthy margin", true));
        } else if (s.margin() < 0) {
            BigDecimal m = f.marginPct();
            reasons.add(new ScoreReason("Thin margin"
                    + (m == null ? "" : " (" + Fmt.toFixed(m.doubleValue(), 0) + "%)"), false));
        }
        if (s.cover() > 0) {
            reasons.add(new ScoreReason("Healthy inventory", true));
        } else if (s.cover() < 0) {
            BigDecimal weeks = f.weeksOfCover();
            reasons.add(weeks != null && weeks.doubleValue() > 16
                    ? new ScoreReason("Overstocked (" + Fmt.toFixed(weeks.doubleValue(), 0) + " weeks of cover)", false)
                    : new ScoreReason("Low stock", false));
        }
        if (s.followRate() > 0) {
            reasons.add(new ScoreReason("High follow-rate on past recommendations", true));
        } else if (s.followRate() < 0) {
            reasons.add(new ScoreReason("Low follow-rate on past recommendations", false));
        }
        if (reasons.isEmpty()) {
            reasons.add(new ScoreReason("Not enough signal yet to explain this score", false));
        }

        List<ScoreReason> sorted = new ArrayList<>(reasons);
        sorted.sort((a, b) -> Boolean.compare(!a.good(), !b.good()));
        if (PricingMath.Score.RISK.equals(s.tier())) {
            Collections.reverse(sorted);
        }
        return sorted.size() > 5 ? sorted.subList(0, 5) : sorted;
    }
}
