package com.aatlas.insights.internal;

import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.List;

/**
 * A port of {@code mock/pricing.ts}'s {@code getPricingModel} - the "single source of price
 * truth" every sell, buy and scoring engine in the prototype reads from.
 *
 * <p>Trimmed to the fields the engines this module owns actually read (see the wave-2
 * brief: {@code geo.ts}, {@code demographics.ts}, {@code overview.ts}, {@code score.ts}).
 * Dropped: {@code aggressivePrice}, {@code recommendedTier}, {@code segment},
 * {@code totalTransactions}, {@code totalCompanies}, {@code observedMin}/{@code Max} -
 * these only feed the Sell screen's calc-steps panel and price-band histogram, which
 * belong to the {@code sell} module, not to Overview, Insights, Stores or Products.
 * {@code priceable} is read straight from wave 1's {@code product_stores} table rather
 * than re-derived from {@code tenantsSellingItem}'s hash rule, which {@code catalog}'s
 * {@code SellersRule} already ports exactly and used to seed those rows.
 */
final class PricingEngine {

    private PricingEngine() {
    }

    record Demand(String level, String label, double movePercent) {
    }

    record PricingModel(
            String item,
            String storeId,
            boolean priceable,
            double cost,
            double currentPrice,
            double peerQ1,
            double peerQ2,
            double peerQ3,
            List<Double> competitorPrices,
            Double competitorMedian,
            double ceilingPrice,
            double optimalPrice,
            Demand demand) {
    }

    /** {@code marginPercent(price, cost)}: {@code null} in the TypeScript becomes 0 here (never both null in this module). */
    static double marginPercent(double price, double cost) {
        if (price == 0) {
            return 0;
        }
        return Fmt.round2(((price - cost) / price) * 100);
    }

    /** The ten domains {@code buildCompetitors} draws from, in the frontend's declared order. */
    private static final String[] COMPETITOR_DOMAINS = {
        "northline.example.com", "castellan.example.com", "redhawk.example.com", "vantage.example.com",
        "ironbridge.example.com", "cobalt.example.com", "fairhaven.example.com", "pemberton.example.com",
        "granite.example.com", "larkspur.example.com",
    };

    static PricingModel compute(String item, String storeId, CatalogSnapshot snapshot) {
        String key = item + "|" + storeId;
        boolean priceable = snapshot.priceable(item, storeId);

        double cost = baseCost(item);
        double currentPrice = Fmt.round2(cost * Seeded.randRange(key, "current", 1.32, 2.15));
        double peerQ2 = Fmt.round2(cost * Seeded.randRange(item, "peer", 1.45, 2.1));
        double peerQ1 = Fmt.round2(peerQ2 * 0.88);
        double peerQ3 = Fmt.round2(peerQ2 * 1.19);

        List<Double> competitors = buildCompetitors(item, storeId, currentPrice);
        Double competitorMedian = competitors.isEmpty()
                ? null
                : Fmt.round2(sortedCopy(competitors).get(competitors.size() / 2));

        double hardFloor = Fmt.round2(cost / 0.75);
        double ceilingPrice = Fmt.round2(Math.max(peerQ3, (competitorMedian == null ? peerQ3 : competitorMedian) * 1.18));

        StoreRef store = snapshot.store(storeId).orElse(null);
        java.math.BigDecimal rpp = store == null ? null : store.rpp();
        boolean msaOff = Seeded.rand(key, "msa-mode") > 0.94;
        double mult = (rpp == null || msaOff) ? 1 : 1 - ((rpp.doubleValue() - 100) / 100) * 0.55;

        double anchor = competitorMedian != null ? competitorMedian : peerQ1;
        double optimal = anchor * Seeded.randRange(key, "opt", 0.95, 1.04) * mult;

        Demand demand = buildDemand(item, storeId);
        if (demand != null) {
            optimal = optimal * (1 + demand.movePercent() / 100);
        }
        double optimalPrice = Fmt.round2(Math.min(Math.max(optimal, hardFloor), ceilingPrice));

        return new PricingModel(item, storeId, priceable, cost, currentPrice, peerQ1, peerQ2, peerQ3,
                competitors, competitorMedian, ceilingPrice, optimalPrice, demand);
    }

    private static double baseCost(String item) {
        double r = Seeded.rand(item, "cost");
        long tier = Seeded.hashString(item) % 10;
        if (tier <= 4) {
            return Fmt.round2(1.2 + r * 12);
        }
        if (tier <= 8) {
            return Fmt.round2(18 + r * 120);
        }
        return Fmt.round2(320 + r * 900);
    }

    private static Demand buildDemand(String item, String storeId) {
        String key = item + "|" + storeId;
        boolean applied = Seeded.rand(key, "demand-applied") > 0.25;
        if (!applied) {
            return null;
        }
        double roll = Seeded.rand(key, "demand-level");
        String level = roll > 0.62 ? "high" : roll > 0.3 ? "medium" : "low";
        double maxAdjustmentPct = 3;
        double confWeight = Fmt.round2(Seeded.randRange(key, "demand-conf", 0.35, 0.95));
        int direction = level.equals("high") ? 1 : level.equals("low") ? -1 : 0;
        double movePercent = Math.round(direction * maxAdjustmentPct * confWeight * 10) / 10.0;
        String label = level.equals("high") ? "High demand" : level.equals("low") ? "Low demand" : "Stable demand";
        return new Demand(level, label, movePercent);
    }

    private static List<Double> buildCompetitors(String item, String storeId, double currentPrice) {
        String key = item + "|" + storeId;
        if (Seeded.rand(key, "comp-none") > 0.86) {
            return List.of();
        }
        int count = 4 + (int) Math.floor(Seeded.rand(key, "comp-count") * 6);
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String domain = COMPETITOR_DOMAINS[i];
            double spread = Seeded.randRange(key, "comp-" + domain, 0.82, 1.24);
            prices.add(Fmt.round2(currentPrice * spread));
        }
        return prices;
    }

    private static List<Double> sortedCopy(List<Double> values) {
        List<Double> copy = new ArrayList<>(values);
        copy.sort(Double::compareTo);
        return copy;
    }
}
