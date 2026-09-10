package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.PricingEngine.marginPercent;
import static com.aatlas.sell.internal.engine.Round.clamp;
import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.seed.Seeded;
import com.aatlas.sell.OpportunityScoreView;
import com.aatlas.sell.OpportunityScoreView.Reason;
import com.aatlas.sell.OpportunityScoreView.Signals;
import com.aatlas.sell.OpportunityScores;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.StoreRef;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/intel/score.ts}: {@code opportunityScore}, {@code tierLabel}. Five
 * signals, each stated as a reason - demand, price against the market, margin health,
 * inventory position, conversion - nothing hidden in the weighting.
 */
@Component
public class ScoreEngine implements OpportunityScores {

    private final CatalogGateway catalog;
    private final PricingEngine pricing;

    public ScoreEngine(CatalogGateway catalog, PricingEngine pricing) {
        this.catalog = catalog;
        this.pricing = pricing;
    }

    public static String tierLabel(String tier) {
        return switch (tier) {
            case "strong" -> "Strong opportunity";
            case "watch" -> "Watch";
            default -> "Risk";
        };
    }

    @Override
    public OpportunityScoreView score(String itemNumber, String storeCode) {
        PricingModel m = pricing.getPricingModel(itemNumber, storeCode);
        String key = "score:" + itemNumber + ":" + storeCode;

        if (!m.priceable()) {
            return new OpportunityScoreView(itemNumber, storeCode, 0, "risk", "No history",
                    List.of(new Reason("No sales history at this store", false)),
                    new Signals("none", bd(0), bd(0), bd(0), 0, bd(0)));
        }

        List<Reason> reasons = new ArrayList<>();
        double score = 42;

        String demandLevel = m.demand() != null ? m.demand().level() : "none";
        if (demandLevel.equals("high")) {
            score += 16;
            reasons.add(new Reason("Demand rising", true));
        } else if (demandLevel.equals("medium")) {
            score += 5;
            reasons.add(new Reason("Demand steady", true));
        } else if (demandLevel.equals("low")) {
            score -= 14;
            reasons.add(new Reason("Demand falling", false));
        }

        double market = m.competitorMedian().orElse(m.peerQ2());
        double priceGapPct = round1(((market - m.currentPrice()) / m.currentPrice()) * 100);
        if (priceGapPct > 3) {
            score += 16;
            reasons.add(new Reason("Your price " + Fmt.fixed(priceGapPct, 1) + "% below market", true));
        } else if (priceGapPct >= 0) {
            score += 5;
            reasons.add(new Reason("Priced at market", true));
        } else if (priceGapPct > -6) {
            score -= 8;
            reasons.add(new Reason("Priced " + Fmt.fixed(Math.abs(priceGapPct), 1) + "% above market", false));
        } else {
            score -= 16;
            reasons.add(new Reason("Priced " + Fmt.fixed(Math.abs(priceGapPct), 1) + "% above market", false));
        }

        ProductRef product = catalog.findProduct(itemNumber).orElse(null);
        String commodityKey = product != null ? product.commodity() : "none";
        CommodityTrend commodity = catalog.commodityTrend(commodityKey);
        double commodityPct90 = commodity.pct90();
        if (commodityPct90 >= 2) {
            score += 6;
            reasons.add(new Reason("Market price rising", true));
        } else if (commodityPct90 <= -1.5) {
            score -= 5;
            reasons.add(new Reason("Market price softening", false));
        }

        double marginPct = marginPercent(m.currentPrice(), m.cost());
        if (marginPct >= 30) {
            score += 5;
            reasons.add(new Reason("Healthy margin", true));
        } else if (marginPct < 22) {
            score -= 9;
            reasons.add(new Reason("Thin margin (" + Math.round(marginPct) + "%)", false));
        }

        int units = SellEngine.monthlyUnitsFor(itemNumber, storeCode, m.cost());
        double weeksOfCover = SellEngine.inventoryFor(itemNumber, storeCode, units).weeksOfCover();
        if (weeksOfCover >= 4 && weeksOfCover <= 12) {
            score += 7;
            reasons.add(new Reason("Healthy inventory", true));
        } else if (weeksOfCover > 16) {
            score -= 10;
            reasons.add(new Reason("Overstocked (" + Math.round(weeksOfCover) + " weeks of cover)", false));
        } else if (weeksOfCover < 3) {
            score -= 5;
            reasons.add(new Reason("Low stock", false));
        }

        int conversionPct = (int) Math.round(Seeded.randRange(key, "conv", 44, 93));
        if (conversionPct >= 75) {
            score += 6;
            reasons.add(new Reason("High conversion", true));
        } else if (conversionPct < 55) {
            score -= 7;
            reasons.add(new Reason("Low conversion", false));
        }

        int finalScore = (int) Math.max(5, Math.min(97, Math.round(score)));
        String tier = finalScore >= 75 ? "strong" : finalScore >= 45 ? "watch" : "risk";

        reasons.sort((a, b) -> a.good() == b.good() ? 0 : a.good() ? -1 : 1);
        if (tier.equals("risk")) {
            java.util.Collections.reverse(reasons);
        }
        List<Reason> top5 = reasons.subList(0, Math.min(5, reasons.size()));

        return new OpportunityScoreView(itemNumber, storeCode, finalScore, tier, tierLabel(tier), List.copyOf(top5),
                new Signals(demandLevel, bd(priceGapPct), bd(round1(marginPct)), bd(weeksOfCover), conversionPct,
                        bd(commodityPct90)));
    }

    @Override
    public List<OpportunityScoreView> rankRegion(String regionKey, int limit) {
        List<StoreRef> stores = catalog.allStores().stream()
                .filter(s -> regionKey == null || regionKey.isBlank() || regionKey.equals(s.regionKey()))
                .toList();
        List<ProductRef> products = catalog.sellableProducts();
        List<OpportunityScoreView> all = new ArrayList<>();
        for (ProductRef p : products) {
            for (StoreRef s : stores) {
                OpportunityScoreView v = score(p.itemNumber(), s.storeCode());
                if (v.score() > 0) {
                    all.add(v);
                }
            }
        }
        return all.stream()
                .sorted(Comparator.comparingInt(OpportunityScoreView::score).reversed())
                .limit(Math.max(0, limit))
                .toList();
    }
}
