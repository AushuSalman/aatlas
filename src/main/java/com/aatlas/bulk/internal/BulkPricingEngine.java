package com.aatlas.bulk.internal;

import com.aatlas.bulk.internal.BulkSeedCatalog.SeedProduct;
import com.aatlas.bulk.internal.BulkSeedCatalog.SeedStore;
import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Exact port of the frontend's pricing engine ({@code src/lib/mock/pricing.ts}'s
 * {@code getPricingModel}), the pure function every price in the demo derives from.
 *
 * <p>Not a stand-in for an engine another module owns - {@code mock/pricing.ts} is shared
 * foundation every intelligence layer (sell, buy, score) calls, ported here because bulk
 * needs it and nothing else in this worktree has. Pinned field for field against
 * {@code golden/pricing-model.json} by {@code PricingEngineGoldenTest}. The one stand-in
 * is upstream of this class: which item/store facts feed it - see {@link BulkSeedCatalog}.
 */
@Component
public class BulkPricingEngine {

    /** Where the underlying commodity is heading over the next quarter. Ported verbatim
     * from {@code src/lib/intel/catalog.ts}'s {@code COMMODITY_TREND}. */
    public record CommodityTrend(double pct90, String label) {
    }

    private static final java.util.Map<String, CommodityTrend> COMMODITY_TREND = java.util.Map.ofEntries(
            java.util.Map.entry("copper", new CommodityTrend(6.4, "Copper up on the index")),
            java.util.Map.entry("brass", new CommodityTrend(3.1, "Brass following copper")),
            java.util.Map.entry("steel", new CommodityTrend(-2.2, "Steel easing")),
            java.util.Map.entry("pvc", new CommodityTrend(0.8, "Resin flat")),
            java.util.Map.entry("pex", new CommodityTrend(1.6, "Resin slightly firmer")),
            java.util.Map.entry("iron", new CommodityTrend(-1.1, "Iron soft")),
            java.util.Map.entry("equipment", new CommodityTrend(2.4, "Equipment list prices rising")),
            java.util.Map.entry("none", new CommodityTrend(0, "No commodity exposure")));

    /** Invented distributors: {@code src/lib/mock/catalog.ts}'s {@code COMPETITOR_DOMAINS}. */
    private static final List<String> COMPETITOR_DOMAINS = List.of(
            "northline.example.com", "castellan.example.com", "redhawk.example.com",
            "vantage.example.com", "ironbridge.example.com", "cobalt.example.com",
            "fairhaven.example.com", "pemberton.example.com", "granite.example.com",
            "larkspur.example.com");

    private final BulkSeedCatalog catalog;

    public BulkPricingEngine(BulkSeedCatalog catalog) {
        this.catalog = catalog;
    }

    public static double round2(double n) {
        return Math.round(n * 100) / 100.0;
    }

    public static double round1(double n) {
        return Math.round(n * 10) / 10.0;
    }

    public static CommodityTrend commodityTrend(String commodity) {
        return COMMODITY_TREND.getOrDefault(commodity, COMMODITY_TREND.get("none"));
    }

    /** {@code src/lib/mock/pricing.ts}'s {@code baseCost}. */
    public double baseCost(String item) {
        double r = Seeded.rand(item, "cost");
        long tier = Seeded.hashString(item) % 10;
        if (tier <= 4) {
            return round2(1.2 + r * 12);
        }
        if (tier <= 8) {
            return round2(18 + r * 120);
        }
        return round2(320 + r * 900);
    }

    /**
     * {@code src/lib/mock/catalog.ts}'s {@code tenantsSellingItem}: which of the nine US
     * branches (in seed order - the index is the seed) have sold this item.
     */
    public List<String> tenantsSellingItem(String item) {
        Optional<SeedProduct> product = catalog.product(item);
        if (product.isEmpty() || !product.get().sellable()) {
            return List.of();
        }
        int seed = (int) Seeded.hashString(product.get().itemNumber());
        List<SeedStore> stores = catalog.stores();
        List<String> sellers = new ArrayList<>();
        for (int i = 0; i < stores.size(); i++) {
            SeedStore t = stores.get(i);
            if (t.storeId().equals(product.get().defaultTenant()) || (seed >> i) % 3 != 0) {
                sellers.add(t.storeId());
            }
        }
        return sellers;
    }

    public boolean sellsAt(String item, String storeId) {
        return storeId == null || storeId.isBlank() || tenantsSellingItem(item).contains(storeId);
    }

    /** Demand signal. Absent for roughly one item in four. */
    public record Demand(String level, String label, double movePercent, double confWeight) {
    }

    private Demand buildDemand(String item, String storeId) {
        String key = item + "|" + storeId;
        if (Seeded.rand(key, "demand-applied") <= 0.25) {
            return null;
        }
        double roll = Seeded.rand(key, "demand-level");
        String level = roll > 0.62 ? "high" : roll > 0.3 ? "medium" : "low";
        double maxAdjustmentPct = 3;
        double confWeight = round2(Seeded.randRange(key, "demand-conf", 0.35, 0.95));
        int direction = level.equals("high") ? 1 : level.equals("low") ? -1 : 0;
        double movePercent = Math.round(direction * maxAdjustmentPct * confWeight * 10) / 10.0;
        String label = level.equals("high") ? "High demand" : level.equals("low") ? "Low demand" : "Stable demand";
        return new Demand(level, label, movePercent, confWeight);
    }

    private List<Double> competitorPrices(String item, String storeId, double currentPrice) {
        String key = item + "|" + storeId;
        if (Seeded.rand(key, "comp-none") > 0.86) {
            return List.of();
        }
        int count = 4 + (int) Math.floor(Seeded.rand(key, "comp-count") * 6);
        List<Double> prices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String domain = COMPETITOR_DOMAINS.get(i);
            double spread = Seeded.randRange(key, "comp-" + domain, 0.82, 1.24);
            prices.add(round2(currentPrice * spread));
        }
        return prices;
    }

    private static Double median(List<Double> sortedAscending) {
        if (sortedAscending.isEmpty()) {
            return null;
        }
        return sortedAscending.get(sortedAscending.size() / 2);
    }

    /** The single resolver every consumer goes through. Exact port of {@code getPricingModel}. */
    public PricingModel getPricingModel(String item, String storeId) {
        String key = item + "|" + storeId;
        Optional<SeedProduct> product = catalog.product(item);
        Optional<SeedStore> tenant = catalog.store(storeId);
        boolean priceable = product.isPresent() && product.get().sellable() && sellsAt(item, storeId);

        double cost = baseCost(item);
        double currentPrice = round2(cost * Seeded.randRange(key, "current", 1.32, 2.15));
        double peerQ2 = round2(cost * Seeded.randRange(item, "peer", 1.45, 2.1));
        double peerQ1 = round2(peerQ2 * 0.88);
        double peerQ3 = round2(peerQ2 * 1.19);

        List<Double> competitors = competitorPrices(item, storeId, currentPrice);
        List<Double> sorted = new ArrayList<>(competitors);
        sorted.sort(Double::compareTo);
        Double competitorMedian = median(sorted);

        double hardFloor = round2(cost / 0.75);
        double ceilingPrice = round2(Math.max(peerQ3, (competitorMedian != null ? competitorMedian : peerQ3) * 1.18));

        Double rpp = tenant.map(SeedStore::rpp).orElse(null);
        String msaMode = Seeded.rand(key, "msa-mode") > 0.94 ? "off" : "competition";
        double mult = (rpp == null || msaMode.equals("off")) ? 1 : 1 - ((rpp - 100) / 100) * 0.55;

        double anchor = competitorMedian != null ? competitorMedian : peerQ1;
        double optimal = anchor * Seeded.randRange(key, "opt", 0.95, 1.04) * mult;
        Demand demand = buildDemand(item, storeId);
        if (demand != null) {
            optimal = optimal * (1 + demand.movePercent() / 100);
        }
        double optimalPrice = round2(Math.min(Math.max(optimal, hardFloor), ceilingPrice));
        double aggressivePrice = round2(Math.min(
                Math.max(optimalPrice * Seeded.randRange(key, "agg", 1.06, 1.19), optimalPrice + 0.05),
                ceilingPrice * 1.02));

        String segment = tenant.map(SeedStore::segment).orElse("occasional");
        double msaMultRounded = Math.round(mult * 10000) / 10000.0;
        int totalTransactions = (int) Math.round(Seeded.randRange(key, "txns", 8, 240));

        return new PricingModel(item, storeId, priceable, cost, currentPrice, optimalPrice, aggressivePrice,
                hardFloor, ceilingPrice, peerQ1, peerQ2, peerQ3, competitorMedian, competitors.size(),
                msaMultRounded, msaMode, demand, segment, totalTransactions);
    }

    /**
     * Field-for-field subset of the TypeScript's {@code PricingModel} - {@code floorPrice},
     * {@code totalCompanies} and {@code observedMin/Max} are computed by
     * {@code getPricingModel} internally where the arithmetic depends on them (ceiling,
     * median) but not exposed here because nothing downstream in bulk or the assistant
     * reads them; {@code PricingEngineGoldenTest} still checks every field this record
     * does carry against the golden file.
     */
    public record PricingModel(
            String item,
            String storeId,
            boolean priceable,
            double cost,
            double currentPrice,
            double optimalPrice,
            double aggressivePrice,
            double marginFloor,
            double ceilingPrice,
            double peerQ1,
            double peerQ2,
            double peerQ3,
            Double competitorMedian,
            int competitorCount,
            double msaMult,
            String msaMode,
            Demand demand,
            String segment,
            int totalTransactions) {
    }
}
