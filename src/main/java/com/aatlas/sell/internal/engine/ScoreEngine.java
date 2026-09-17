package com.aatlas.sell.internal.engine;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.decisions.DealSummaries.Adoption;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PricingMath.Score;
import com.aatlas.history.PricingMath.ScoreInputs;
import com.aatlas.history.Window;
import com.aatlas.sell.OpportunityScoreView;
import com.aatlas.sell.OpportunityScoreView.Reason;
import com.aatlas.sell.OpportunityScoreView.Signals;
import com.aatlas.sell.OpportunityScores;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/intel/score.ts}: {@code opportunityScore}, {@code tierLabel}, now
 * over {@link com.aatlas.history.PricingMath#score} so sell, insights and bulk agree bit for
 * bit. Five signals, each stated as a reason - demand, price against the market, margin
 * health, inventory position, decision follow-rate - nothing hidden in the weighting.
 */
@Component
public class ScoreEngine implements OpportunityScores {

    private final CatalogGateway catalog;
    private final PricingEngine pricing;
    private final DealSummaries deals;
    private final AatlasClock clock;

    public ScoreEngine(CatalogGateway catalog, PricingEngine pricing, DealSummaries deals, AatlasClock clock) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.deals = deals;
        this.clock = clock;
    }

    public static String tierLabel(String tier) {
        return switch (tier) {
            case Score.STRONG -> "Strong opportunity";
            case Score.WATCH -> "Watch";
            default -> "Risk";
        };
    }

    @Override
    public OpportunityScoreView score(String itemNumber, String storeCode) {
        PricingModel m = pricing.getPricingModel(itemNumber, storeCode);

        if (!m.priceable()) {
            return new OpportunityScoreView(itemNumber, storeCode, 0, "risk", "No history",
                    List.of(new Reason("No sales history at this store", false)),
                    new Signals("none", null, null, null, null, null));
        }

        LocalDate today = clock.today();
        Window w12 = Window.trailingMonths(today, 12);
        Adoption adoption = deals.adoption("sell", w12.from(), w12.to(), storeCode);
        BigDecimal followRate = adoption.followRatePct().map(BigDecimal::valueOf).orElse(null);

        BigDecimal marginPct = PricingMath.marginPct(m.currentPrice(), m.cost());
        BigDecimal weeksOfCover = PricingMath.weeksOfCover(m.onHandUnits(), m.units90());
        String demandLevel = m.demand() != null ? m.demand().level() : null;

        ScoreInputs inputs = new ScoreInputs(demandLevel, m.anchorValue(), m.currentPrice(), m.commodityPct90(),
                marginPct, weeksOfCover, followRate, adoption.total());
        Score s = PricingMath.score(inputs);

        List<Reason> reasons = new ArrayList<>();
        if (s.demand() > 0) {
            reasons.add(new Reason("Demand rising", true));
        } else if (s.demand() < 0) {
            reasons.add(new Reason("Demand falling", false));
        } else if ("medium".equals(demandLevel)) {
            reasons.add(new Reason("Demand steady", true));
        }
        if (s.priceGapPct() != null) {
            double g = s.priceGapPct().doubleValue();
            if (g > 3) {
                reasons.add(new Reason("Your price " + Fmt.fixed(g, 1) + "% below market", true));
            } else if (g >= 0) {
                reasons.add(new Reason("Priced at market", true));
            } else {
                reasons.add(new Reason("Priced " + Fmt.fixed(Math.abs(g), 1) + "% above market", false));
            }
        }
        if (s.commodity() > 0) {
            reasons.add(new Reason("Market price rising", true));
        } else if (s.commodity() < 0) {
            reasons.add(new Reason("Market price softening", false));
        }
        if (s.margin() > 0) {
            reasons.add(new Reason("Healthy margin", true));
        } else if (s.margin() < 0) {
            reasons.add(new Reason("Thin margin"
                    + (marginPct != null ? " (" + Math.round(marginPct.doubleValue()) + "%)" : ""), false));
        }
        if (s.cover() > 0) {
            reasons.add(new Reason("Healthy inventory", true));
        } else if (weeksOfCover != null && weeksOfCover.doubleValue() > 16) {
            reasons.add(new Reason("Overstocked (" + Math.round(weeksOfCover.doubleValue()) + " weeks of cover)", false));
        } else if (s.cover() < 0) {
            reasons.add(new Reason("Low stock", false));
        }
        if (s.followRate() > 0) {
            reasons.add(new Reason("High follow rate", true));
        } else if (s.followRate() < 0) {
            reasons.add(new Reason("Low follow rate", false));
        }

        reasons.sort((a, b) -> a.good() == b.good() ? 0 : a.good() ? -1 : 1);
        if (Score.RISK.equals(s.tier())) {
            java.util.Collections.reverse(reasons);
        }
        List<Reason> top5 = reasons.subList(0, Math.min(5, reasons.size()));

        return new OpportunityScoreView(itemNumber, storeCode, s.score(), s.tier(), tierLabel(s.tier()),
                List.copyOf(top5),
                new Signals(demandLevel == null ? "none" : demandLevel, s.priceGapPct(), marginPct, weeksOfCover,
                        adoption.total() == 0 ? null : (int) Math.round(followRate == null ? 0 : followRate.doubleValue()),
                        m.commodityPct90()));
    }

    @Override
    public List<OpportunityScoreView> rankRegion(String regionKey, int limit) {
        List<com.aatlas.history.Catalogue.StoreRef> stores = catalog.allStores().stream()
                .filter(s -> regionKey == null || regionKey.isBlank() || regionKey.equals(s.regionKey()))
                .toList();
        List<com.aatlas.history.Catalogue.ProductRef> products = catalog.sellableProducts();
        List<OpportunityScoreView> all = new ArrayList<>();
        for (var p : products) {
            for (var s : stores) {
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
