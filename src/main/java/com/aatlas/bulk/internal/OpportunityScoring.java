package com.aatlas.bulk.internal;

import com.aatlas.bulk.internal.PricingEngine.PricingModel;
import com.aatlas.common.seed.Seeded;
import org.springframework.stereotype.Component;

/**
 * Exact port of {@code src/lib/intel/score.ts}'s {@code opportunityScore}: the one number
 * per (item, store) {@code bulkSellPlan} uses for each line's {@code score}/{@code tier}.
 * Five signals - demand, price-vs-market, market direction, margin health, inventory
 * position, and a seeded conversion draw - the same weights as the TypeScript, unchanged.
 */
@Component
public class OpportunityScoring {

    private final PricingEngine pricing;
    private final BulkSeedCatalog catalog;

    public OpportunityScoring(PricingEngine pricing, BulkSeedCatalog catalog) {
        this.pricing = pricing;
        this.catalog = catalog;
    }

    public record Score(int score, String tier) {
    }

    public Score score(String itemNumber, String storeId) {
        PricingModel m = pricing.getPricingModel(itemNumber, storeId);
        if (!m.priceable()) {
            return new Score(0, "risk");
        }
        String key = "score:" + itemNumber + ":" + storeId;
        double score = 42;

        String demandLevel = m.demand() != null ? m.demand().level() : "none";
        if ("high".equals(demandLevel)) {
            score += 16;
        } else if ("medium".equals(demandLevel)) {
            score += 5;
        } else if ("low".equals(demandLevel)) {
            score -= 14;
        }

        double market = m.competitorMedian() != null ? m.competitorMedian() : m.peerQ2();
        double priceGapPct = PricingEngine.round1(((market - m.currentPrice()) / m.currentPrice()) * 100);
        if (priceGapPct > 3) {
            score += 16;
        } else if (priceGapPct >= 0) {
            score += 5;
        } else if (priceGapPct > -6) {
            score -= 8;
        } else {
            score -= 16;
        }

        String commodity = catalog.product(itemNumber).map(BulkSeedCatalog.SeedProduct::commodity).orElse("none");
        double commodityPct90 = PricingEngine.commodityTrend(commodity).pct90();
        if (commodityPct90 >= 2) {
            score += 6;
        } else if (commodityPct90 <= -1.5) {
            score -= 5;
        }

        double marginPct = m.currentPrice() == 0 ? 0
                : PricingEngine.round2(((m.currentPrice() - m.cost()) / m.currentPrice()) * 100);
        if (marginPct >= 30) {
            score += 5;
        } else if (marginPct < 22) {
            score -= 9;
        }

        double units = SellSeeds.monthlyUnitsFor(itemNumber, storeId, m.cost());
        double weeksOfCover = SellSeeds.inventoryFor(itemNumber, storeId, units).weeksOfCover();
        if (weeksOfCover >= 4 && weeksOfCover <= 12) {
            score += 7;
        } else if (weeksOfCover > 16) {
            score -= 10;
        } else if (weeksOfCover < 3) {
            score -= 5;
        }

        double conversionPct = Math.round(Seeded.randRange(key, "conv", 44, 93));
        if (conversionPct >= 75) {
            score += 6;
        } else if (conversionPct < 55) {
            score -= 7;
        }

        int finalScore = (int) Math.max(5, Math.min(97, Math.round(score)));
        String tier = finalScore >= 75 ? "strong" : finalScore >= 45 ? "watch" : "risk";
        return new Score(finalScore, tier);
    }
}
