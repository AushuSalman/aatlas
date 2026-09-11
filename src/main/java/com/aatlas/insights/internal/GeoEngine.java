package com.aatlas.insights.internal;

import com.aatlas.common.seed.Seeded;
import com.aatlas.insights.internal.BuyEngine.BuyRecommendation;
import com.aatlas.insights.internal.PricingEngine.PricingModel;
import com.aatlas.insights.internal.ScoreEngine.OpportunityScore;
import com.aatlas.insights.internal.SellEngine.SellSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A port of {@code intel/geo.ts}: who is buying, where, and what it means for price.
 * Every store figure that can be derived from the pricing engine is; demand climate,
 * revenue and conversion are stated per region with a point of view, exactly as the
 * TypeScript states them - the fixtures were built around the story ("the South is hot,
 * the West earns, the North converts poorly, the East grows"), not fitted to anything.
 */
final class GeoEngine {

    private GeoEngine() {
    }

    record Climate(double demandPct, double marginAdj, double conversionPct, String headline, String note) {
    }

    record StoreOpportunity(String itemNumber, String name, String kind, String action, double impact, String href) {
    }

    record ProductRow(String itemNumber, String name, int score, String tier, double current, double recommended) {
    }

    record StoreIntel(
            String storeId, String label, String city, String state, String regionKey, String regionLabel,
            String regionName, double revenue, double marginPct, int pricingAccuracyPct, int adoptionPct,
            double inventoryValue, String inventoryRisk, double demandPct, int conversionPct,
            String health, String healthLabel, String healthNote,
            List<StoreOpportunity> opportunities, List<ProductRow> products) {
    }

    record RegionAction(String label, int count, String href) {
    }

    record FastestGrowing(String itemNumber, String name, double pct) {
    }

    record RegionIntel(
            String key, String label, String name, List<String> storeIds, List<StoreIntel> stores,
            double revenue, double demandPct, double avgSellingPrice, double avgMarginPct, double conversionPct,
            double priceTrendPct, String inventoryRisk, String topCategory, FastestGrowing fastestGrowing,
            String headline, String climateNote, List<RegionAction> actions,
            List<String> raisePrices, List<String> increaseInventory, List<String> reviewSuppliers) {
    }

    // -- The regional climate: stated once, read everywhere (US variant; the fixtures use the
    // same numbers for every country, only the prose differs) -----------------------------
    private static final Map<String, Climate> CLIMATE = Map.of(
            "south", new Climate(14.2, 0.2, 71, "High demand",
                    "Hot, humid summers: cooling season drives HVAC, PVC and water-heater demand."),
            "west", new Climate(4.1, 2.6, 67, "High margin",
                    "Dry heat and long build seasons: steady PEX and fittings volume at the best margins in the network."),
            "north", new Climate(-2.6, -0.9, 52, "Low conversion",
                    "Cold winters and a short build season: heating and copper spike in autumn, then the quotes go quiet."),
            "east", new Climate(9.3, 0.6, 64, "Growing demand",
                    "Four full seasons and dense housing: renovation work keeps fixtures and valves moving all year."));

    static Climate regionClimate(String key) {
        return CLIMATE.get(key);
    }

    static String storeCity(StoreRef store) {
        String source = store.msaName() != null ? store.msaName()
                : store.legalName() != null ? store.legalName() : store.storeCode();
        return source.split("-")[0].replace(" Branch", "").trim();
    }

    static String storeLabel(String storeId, CatalogSnapshot snapshot) {
        StoreRef store = snapshot.store(storeId).orElse(null);
        String city = store == null ? storeId : storeCity(store);
        return city + " #" + storeId;
    }

    /** {@code platform/data.ts}'s {@code storeName}: the network-node name used as a deal counterparty. */
    static String storeName(StoreRef store) {
        String source = store.msaName() != null ? store.msaName() : "Branch";
        return source.split("-")[0] + " — " + store.state();
    }

    static double storeSeed(String storeId, String salt) {
        return Seeded.rand("store:" + storeId, salt);
    }

    static StoreIntel getStoreIntel(String storeId, CatalogSnapshot snapshot, DealsIndex deals) {
        StoreRef store = snapshot.store(storeId)
                .orElseThrow(() -> new IllegalStateException("Unknown store " + storeId));
        MarketRegionRef region = snapshot.marketRegionForState(store.state());
        Climate climate = CLIMATE.get(region.key());

        double revW = 0;
        double cogsW = 0;
        int accurate = 0;
        double inventoryValue = 0;
        double coverSum = 0;
        int priceableCount = 0;
        List<ProductRow> products = new ArrayList<>();
        List<StoreOpportunity> opportunities = new ArrayList<>();

        for (ProductRef p : FixtureOrder.sellableProducts(snapshot)) {
            if (!snapshot.priceable(p.itemNumber(), storeId)) {
                continue;
            }
            priceableCount++;
            PricingModel m = PricingEngine.compute(p.itemNumber(), storeId, snapshot);
            SellSummary intel = SellEngine.compute(p.itemNumber(), storeId, snapshot, m);
            OpportunityScore s = ScoreEngine.compute(p.itemNumber(), storeId, snapshot);

            revW += intel.currentPrice() * intel.monthlyUnits();
            cogsW += intel.cost() * intel.monthlyUnits();
            if (Math.abs(intel.recommended() - intel.currentPrice()) / intel.recommended() < 0.05) {
                accurate++;
            }
            inventoryValue += intel.inventoryValue();
            coverSum += intel.weeksOfCover();
            products.add(new ProductRow(p.itemNumber(), intel.name(), s.score(), s.tier(),
                    intel.currentPrice(), intel.recommended()));

            double gapPct = ((intel.recommended() - intel.currentPrice()) / intel.currentPrice()) * 100;
            if (gapPct > 2) {
                opportunities.add(new StoreOpportunity(p.itemNumber(), intel.name(), "price",
                        "Increase price " + Fmt.toFixed(gapPct, 0) + "%", Fmt.round2(intel.monthlyOpportunity()),
                        "/app/sell?item=" + p.itemNumber() + "&store=" + storeId));
            } else if (gapPct < -3) {
                opportunities.add(new StoreOpportunity(p.itemNumber(), intel.name(), "price",
                        "Reduce price " + Fmt.toFixed(Math.abs(gapPct), 0) + "% to win volume",
                        Fmt.round2(Math.abs(intel.monthlyOpportunity()) * 0.6),
                        "/app/sell?item=" + p.itemNumber() + "&store=" + storeId));
            }
            // Suppliers seed asynchronously right after a data source connects (see
            // SuppliersSeedListener); a request that lands in that narrow window would
            // otherwise hit BuyEngine.currentSupplierFor with an empty panel. Treat "not
            // seeded yet" the same as "no supplier opportunity to report" rather than 500.
            BuyRecommendation buy = snapshot.suppliers().isEmpty() ? null
                    : BuyEngine.compute(p.itemNumber(), storeId, snapshot);
            if (buy != null && buy.savingPct() > 4) {
                opportunities.add(new StoreOpportunity(p.itemNumber(), intel.name(), "supplier",
                        "Supplier renegotiation, " + Fmt.toFixed(buy.savingPct(), 0) + "% over target",
                        Fmt.round2(buy.annualSaving() / 12),
                        "/app/buy?item=" + p.itemNumber() + "&region=" + region.key()));
            }
            if (m.demand() != null && "low".equals(m.demand().level())) {
                opportunities.add(new StoreOpportunity(p.itemNumber(), intel.name(), "demand",
                        "Declining demand, review stock", Fmt.round2(intel.inventoryValue() * 0.02),
                        "/app/products?item=" + p.itemNumber()));
            }
            if (intel.weeksOfCover() > 16) {
                opportunities.add(new StoreOpportunity(p.itemNumber(), intel.name(), "inventory",
                        "Overstocked, " + Fmt.toFixed(intel.weeksOfCover(), 0) + " weeks of cover",
                        Fmt.round2(intel.inventoryValue() * 0.015),
                        "/app/sell/bulk?store=" + storeId + "&preset=overstock"));
            }
        }
        opportunities.sort((a, b) -> Double.compare(b.impact(), a.impact()));
        products.sort((a, b) -> Integer.compare(b.score(), a.score()));

        double marginPct = revW > 0 ? Fmt.round1(((revW - cogsW) / revW) * 100 + climate.marginAdj()) : 0;
        int pricingAccuracyPct = priceableCount > 0 ? (int) Math.round(accurate * 100.0 / priceableCount) : 0;
        double avgCover = priceableCount > 0 ? coverSum / priceableCount : 0;
        String inventoryRisk = avgCover > 13 ? "High" : avgCover > 9 ? "Medium" : "Low";

        DealsIndex.Adoption adoption = deals.adoptionFor(storeName(store));
        int adoptionPct = adoption.any()
                ? (int) Math.round(adoption.followed() * 100.0 / adoption.total())
                : (int) Math.round(Seeded.randRange("adopt:" + storeId, "a", 58, 84));

        int txns = store.txns() != null ? store.txns() : 8000;
        double revenue = Math.round(txns * Seeded.randRange("rev:" + storeId, "ticket", 96, 240));
        double demandPct = Fmt.round1(climate.demandPct() + (storeSeed(storeId, "demand") - 0.5) * 6);
        int conversionPct = (int) Math.round(climate.conversionPct() + (storeSeed(storeId, "conv") - 0.5) * 10);

        int points = 0;
        if (marginPct >= 27) {
            points++;
        }
        if (pricingAccuracyPct >= 40) {
            points++;
        }
        if (adoptionPct >= 62) {
            points++;
        }
        if (!"High".equals(inventoryRisk)) {
            points++;
        }
        String health = points >= 3 ? "healthy" : points == 2 ? "watch" : "attention";

        List<String> issues = new ArrayList<>();
        if (marginPct < 27) {
            issues.add("margin is thin");
        }
        if (pricingAccuracyPct < 40) {
            issues.add("prices drift from the recommendation");
        }
        if (adoptionPct < 62) {
            issues.add("too many recommendations are ignored");
        }
        if ("High".equals(inventoryRisk)) {
            issues.add("inventory is heavy");
        }
        String issueText = issues.isEmpty() ? "" : capitalise(String.join(", ", issues)) + ".";
        String healthNote = switch (health) {
            case "healthy" -> issues.isEmpty()
                    ? "Margin, pricing accuracy and adoption all where they should be."
                    : "Broadly on track. " + issueText;
            case "watch" -> issueText;
            default -> "Several signals need attention this month. " + issueText;
        };

        return new StoreIntel(storeId, storeLabel(storeId, snapshot), storeCity(store),
                store.state() == null ? "" : store.state(), region.key(), region.shortLabel(), region.fullLabel(),
                revenue, marginPct, pricingAccuracyPct, adoptionPct, Fmt.round2(inventoryValue), inventoryRisk,
                demandPct, conversionPct, health, healthLabel(health), healthNote,
                opportunities.size() > 6 ? opportunities.subList(0, 6) : opportunities, products);
    }

    private static String healthLabel(String health) {
        return switch (health) {
            case "healthy" -> "Healthy";
            case "watch" -> "Watch";
            default -> "Needs attention";
        };
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static RegionIntel getRegionIntel(String key, CatalogSnapshot snapshot, DealsIndex deals) {
        MarketRegionRef region = snapshot.marketRegion(key);
        Climate climate = CLIMATE.get(key);
        List<String> storeIds = FixtureOrder.stores(snapshot).stream()
                .filter(s -> snapshot.marketRegionForState(s.state()).key().equals(key))
                .map(StoreRef::storeCode)
                .toList();
        List<StoreIntel> stores = storeIds.stream().map(id -> getStoreIntel(id, snapshot, deals)).toList();

        double revenue = stores.stream().mapToDouble(StoreIntel::revenue).sum();
        double avgMarginPct = stores.isEmpty() ? 0
                : Fmt.round1(stores.stream().mapToDouble(StoreIntel::marginPct).average().orElse(0));

        List<Double> prices = new ArrayList<>();
        List<Double> drifts = new ArrayList<>();
        Map<String, Double> categoryWeight = new LinkedHashMap<>();
        FastestGrowing fastest = new FastestGrowing("", "", Double.NEGATIVE_INFINITY);
        Set<String> raisePrices = new LinkedHashSet<>();
        Set<String> increaseInventory = new LinkedHashSet<>();
        Set<String> reviewSuppliers = new LinkedHashSet<>();

        for (String storeId : storeIds) {
            for (ProductRef p : FixtureOrder.sellableProducts(snapshot)) {
                if (!snapshot.priceable(p.itemNumber(), storeId)) {
                    continue;
                }
                PricingModel m = PricingEngine.compute(p.itemNumber(), storeId, snapshot);
                SellSummary intel = SellEngine.compute(p.itemNumber(), storeId, snapshot, m);
                prices.add(m.currentPrice());
                double driftPct = priceDriftPct90(m, p, snapshot);
                drifts.add(driftPct);
                categoryWeight.merge(p.category(), intel.currentPrice() * intel.monthlyUnits(), Double::sum);
                double growth = (m.demand() != null ? m.demand().movePercent() * 4 : 0) + driftPct;
                if (growth > fastest.pct()) {
                    fastest = new FastestGrowing(p.itemNumber(), intel.name(), Fmt.round1(growth));
                }
                if ((intel.recommended() - intel.currentPrice()) / intel.currentPrice() > 0.02) {
                    raisePrices.add(p.itemNumber());
                }
                if (m.demand() != null && "high".equals(m.demand().level()) && intel.weeksOfCover() < 5) {
                    increaseInventory.add(p.itemNumber());
                }
                BuyRecommendation buy = snapshot.suppliers().isEmpty() ? null
                        : BuyEngine.compute(p.itemNumber(), storeId, snapshot);
                if (buy != null && buy.savingPct() > 4) {
                    reviewSuppliers.add(buy.incumbentSupplierName());
                }
            }
        }
        String topCategory = categoryWeight.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Plumbing");
        double avgSellingPrice = prices.isEmpty() ? 0
                : Fmt.round2(prices.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        double priceTrendPct = drifts.isEmpty() ? 0
                : Fmt.round1(drifts.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        Set<String> risks = stores.stream().map(StoreIntel::inventoryRisk).collect(java.util.stream.Collectors.toSet());
        String inventoryRisk = risks.contains("High") ? "High" : risks.contains("Medium") ? "Medium" : "Low";

        List<RegionAction> actions = List.of(
                new RegionAction("Increase inventory", increaseInventory.size(), "/app/products?region=" + key + "&filter=strong"),
                new RegionAction("Adjust prices", raisePrices.size(), "/app/sell/bulk?region=" + key),
                new RegionAction("Review suppliers", reviewSuppliers.size(), "/app/buy/bulk?region=" + key));

        return new RegionIntel(key, region.shortLabel(), region.fullLabel(), storeIds, stores, revenue,
                climate.demandPct(), avgSellingPrice, avgMarginPct, climate.conversionPct(), priceTrendPct,
                inventoryRisk, topCategory,
                fastest.itemNumber().isEmpty() ? new FastestGrowing("", "—", 0) : fastest,
                climate.headline(), climate.note(), actions,
                List.copyOf(raisePrices), List.copyOf(increaseInventory), List.copyOf(reviewSuppliers));
    }

    /**
     * A port of {@code intel/sell.ts}'s {@code priceDriftPct90}: where this item's price is
     * heading over 90 days, as a percent move - commodity trend first, the engine's own
     * demand signal second, a little item-level noise so not every copper line moves
     * identically. Only the {@code pct} half is read by this module; {@code driver} is a
     * Sell-screen prose label.
     */
    static double priceDriftPct90(PricingModel m, ProductRef meta, CatalogSnapshot snapshot) {
        CommodityRef commodity = snapshot.commodity(meta.commodity());
        double demand = m.demand() != null ? m.demand().movePercent() * 1.6 : 0;
        double noise = Fmt.round1(Seeded.randRange(m.item() + "|" + m.storeId(), "fc-noise", -1.4, 1.4));
        return Fmt.round1(commodity.pct90AsDouble() * 0.72 + demand + noise);
    }

    static List<RegionIntel> allRegions(CatalogSnapshot snapshot, DealsIndex deals) {
        return snapshot.marketRegions().stream().map(r -> getRegionIntel(r.key(), snapshot, deals)).toList();
    }

    static List<StoreIntel> allStores(CatalogSnapshot snapshot, DealsIndex deals) {
        return FixtureOrder.stores(snapshot).stream()
                .map(s -> getStoreIntel(s.storeCode(), snapshot, deals))
                .toList();
    }
}
