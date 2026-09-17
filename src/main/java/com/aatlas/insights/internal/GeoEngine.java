package com.aatlas.insights.internal;

import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.PricingMath;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesStats;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec 3.8/3.10: who is buying, where, and what it means for price - over the tenant's real
 * rows. Every branch is {@link SalesHistory#byStore}'s own key (incl. {@code no-branch}, the
 * synthetic branch for sales with no store on file); every region is {@link Catalogue}'s real
 * region for the tenant's country, plus a fifth "Needs a region" bucket for stores/sales with
 * no region assigned - never dropped, just not counted among the four named regions.
 */
@Component
class GeoEngine {

    static final String UNASSIGNED = SalesHistory.GroupStats.UNASSIGNED;
    static final String NO_BRANCH = SalesHistory.GroupStats.NO_BRANCH;

    record StoreOpportunity(String itemNumber, String name, String kind, String action, double impact, String href) {
    }

    record ProductRow(String itemNumber, String name, int score, String tier, Double current, Double recommended) {
    }

    record StoreIntel(
            String storeId, String label, String city, String state, String regionKey, String regionLabel,
            String regionName, double revenue, Double marginPct, Integer pricingAccuracyPct, Integer adoptionPct,
            Double inventoryValue, String inventoryRisk, double demandPct, Integer conversionPct,
            String health, String healthLabel, String healthNote,
            List<StoreOpportunity> opportunities, List<ProductRow> products, List<String> locked) {
    }

    record RegionAction(String label, int count, String href) {
    }

    record FastestGrowing(String itemNumber, String name, double pct) {
    }

    record RegionIntel(
            String key, String label, String name, List<String> storeIds, List<StoreIntel> stores,
            double revenue, double demandPct, Double avgSellingPrice, Double avgMarginPct, Integer conversionPct,
            double priceTrendPct, String inventoryRisk, String topCategory, FastestGrowing fastestGrowing,
            String headline, String climateNote, List<RegionAction> actions,
            List<String> raisePrices, List<String> increaseInventory, List<String> reviewSuppliers,
            List<String> locked) {
    }

    private final BuyEngine buyEngine;

    GeoEngine(BuyEngine buyEngine) {
        this.buyEngine = buyEngine;
    }

    // -- 3.10 health, a pure function so it can be unit-tested without a request --------------

    record HealthResult(String status, String label, String note) {
    }

    static String healthLabel(String health) {
        return switch (health) {
            case "healthy" -> "Healthy";
            case "watch" -> "Watch";
            default -> "Needs attention";
        };
    }

    /**
     * Applicable checks: margin&gt;=27 (cost known), pricing accuracy&gt;=40, adoption&gt;=62
     * (deals exist), inventory risk != High (stock on file). {@code ratio = passed/applicable};
     * &gt;=0.75 healthy, &gt;=0.5 watch, else attention. A null input is a skipped check, noted
     * separately from a failed one.
     */
    static HealthResult computeHealth(Double marginPct, Integer pricingAccuracyPct, Double adoptionPct,
            String inventoryRisk) {
        int applicable = 0;
        int passed = 0;
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        if (marginPct != null) {
            applicable++;
            if (marginPct >= 27) {
                passed++;
            } else {
                failed.add("margin is thin");
            }
        } else {
            skipped.add("no costed sales yet");
        }
        if (pricingAccuracyPct != null) {
            applicable++;
            if (pricingAccuracyPct >= 40) {
                passed++;
            } else {
                failed.add("prices drift from the recommendation");
            }
        }
        if (adoptionPct != null) {
            applicable++;
            if (adoptionPct >= 62) {
                passed++;
            } else {
                failed.add("too many recommendations are ignored");
            }
        } else {
            skipped.add("no decisions yet");
        }
        if (inventoryRisk != null) {
            applicable++;
            if (!"High".equals(inventoryRisk)) {
                passed++;
            } else {
                failed.add("inventory is heavy");
            }
        } else {
            skipped.add("no stock data");
        }

        double ratio = applicable == 0 ? 0 : (double) passed / applicable;
        String status = applicable > 0 && ratio >= 0.75 ? "healthy" : applicable > 0 && ratio >= 0.5 ? "watch" : "attention";
        List<String> notes = new ArrayList<>(failed);
        notes.addAll(skipped);
        String note = notes.isEmpty()
                ? "Margin, pricing accuracy and adoption all where they should be."
                : capitalise(String.join(", ", notes)) + ".";
        return new HealthResult(status, healthLabel(status), note);
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -- 3.8 region headline, a pure function so it can be unit-tested without a request -------

    record RegionMetrics(String key, BigDecimal growthPct, BigDecimal marginPct, BigDecimal revenue) {
    }

    /**
     * Each region gets exactly one headline: the first rule it is the extreme region for, in
     * priority order (growth up, margin, growth down, margin down, revenue), ties broken toward
     * the higher-revenue region. A region that leads no metric reads "Steady".
     */
    static Map<String, String> regionHeadlines(List<RegionMetrics> regions) {
        Map<String, String> out = new LinkedHashMap<>();
        if (regions.isEmpty()) {
            return out;
        }
        RegionMetrics maxGrowth = extreme(regions, RegionMetrics::growthPct, true);
        RegionMetrics maxMargin = extreme(regions, RegionMetrics::marginPct, true);
        RegionMetrics minGrowth = extreme(regions, RegionMetrics::growthPct, false);
        RegionMetrics minMargin = extreme(regions, RegionMetrics::marginPct, false);
        RegionMetrics maxRevenue = extreme(regions, RegionMetrics::revenue, true);

        for (RegionMetrics r : regions) {
            String headline;
            if (r == maxGrowth && r.growthPct() != null && r.growthPct().doubleValue() >= 3) {
                headline = "Growing demand";
            } else if (r == maxMargin && r.marginPct() != null) {
                headline = "High margin";
            } else if (r == minGrowth && r.growthPct() != null && r.growthPct().doubleValue() <= -3) {
                headline = "Softening demand";
            } else if (r == minMargin && r.marginPct() != null) {
                headline = "Thin margin";
            } else if (r == maxRevenue) {
                headline = "Largest market";
            } else {
                headline = "Steady";
            }
            out.put(r.key(), headline);
        }
        return out;
    }

    private static RegionMetrics extreme(List<RegionMetrics> regions, java.util.function.Function<RegionMetrics, BigDecimal> f,
            boolean max) {
        RegionMetrics best = null;
        for (RegionMetrics r : regions) {
            BigDecimal v = f.apply(r);
            if (v == null) {
                continue;
            }
            if (best == null) {
                best = r;
                continue;
            }
            BigDecimal bestValue = f.apply(best);
            int cmp = v.compareTo(bestValue);
            boolean better = max ? cmp > 0 : cmp < 0;
            boolean tie = cmp == 0;
            if (better || (tie && r.revenue().compareTo(best.revenue()) > 0)) {
                best = r;
            }
        }
        return best;
    }

    // -- store / region intel --------------------------------------------------------------

    StoreIntel storeIntel(String storeCode, InsightsData data) {
        boolean noBranch = NO_BRANCH.equals(storeCode);
        var store = noBranch ? null : data.store(storeCode).orElseThrow(() -> notFound(storeCode));
        List<PairFacts> pairs = data.atStore(storeCode);

        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal costedRevenue = BigDecimal.ZERO;
        BigDecimal cogs = BigDecimal.ZERO;
        int priceableCount = 0;
        int accurate = 0;
        BigDecimal inventoryValue = BigDecimal.ZERO;
        boolean anyInventory = false;
        BigDecimal coverSum = BigDecimal.ZERO;
        int coverCount = 0;
        BigDecimal driftSum = BigDecimal.ZERO;
        int driftCount = 0;
        List<ProductRow> products = new ArrayList<>();
        List<StoreOpportunity> opportunities = new ArrayList<>();

        for (PairFacts f : pairs) {
            SalesStats w12 = f.pair().w12();
            if (w12 != null) {
                revenue = revenue.add(w12.revenue() == null ? BigDecimal.ZERO : w12.revenue());
                if (w12.costedRevenue() != null && w12.cogs() != null) {
                    costedRevenue = costedRevenue.add(w12.costedRevenue());
                    cogs = cogs.add(w12.cogs());
                }
            }
            if (f.hasInventory()) {
                anyInventory = true;
                BigDecimal iv = f.inventoryValue();
                if (iv != null) {
                    inventoryValue = inventoryValue.add(iv);
                }
                BigDecimal cover = f.weeksOfCover();
                if (cover != null) {
                    coverSum = coverSum.add(cover);
                    coverCount++;
                }
            }
            if (f.demand() != null) {
                driftSum = driftSum.add(PricingMath.driftPct90(f.commodityPct90(), f.demand().movePercent()));
                driftCount++;
            }
            if (!f.priceable()) {
                continue;
            }
            priceableCount++;
            BigDecimal current = f.currentPrice();
            BigDecimal optimal = f.optimalPrice();
            DealSummaries.Adoption adoption = data.adoptionFor(noBranch ? null : storeCode);
            ScoreEngine.OpportunityScore score = ScoreEngine.compute(f, adoption);
            products.add(new ProductRow(f.pair().itemNumber(), f.pair().shortName(), score.score(), score.tier(),
                    Fmt.dv(current), Fmt.dv(optimal)));

            double gapPct = current.signum() == 0 ? 0
                    : optimal.subtract(current).divide(current, 6, RoundingMode.HALF_UP).doubleValue() * 100;
            if (current.subtract(optimal).abs().divide(optimal.signum() == 0 ? BigDecimal.ONE : optimal, 6,
                    RoundingMode.HALF_UP).doubleValue() < 0.05) {
                accurate++;
            }
            double monthlyOpportunity = Fmt.dv0(f.monthlyOpportunity());
            if (gapPct > 2) {
                opportunities.add(new StoreOpportunity(f.pair().itemNumber(), f.pair().shortName(), "price",
                        "Increase price " + Fmt.toFixed(gapPct, 0) + "%", Fmt.round2(monthlyOpportunity),
                        "/app/sell?item=" + f.pair().itemNumber() + "&store=" + storeCode));
            } else if (gapPct < -3) {
                opportunities.add(new StoreOpportunity(f.pair().itemNumber(), f.pair().shortName(), "price",
                        "Reduce price " + Fmt.toFixed(Math.abs(gapPct), 0) + "% to win volume",
                        Fmt.round2(Math.abs(monthlyOpportunity) * 0.6),
                        "/app/sell?item=" + f.pair().itemNumber() + "&store=" + storeCode));
            }
            buyEngine.compute(f, data.today()).ifPresent(buy -> {
                if (buy.savingPct() != null && buy.savingPct().doubleValue() > 4 && buy.annualSaving() != null) {
                    opportunities.add(new StoreOpportunity(f.pair().itemNumber(), f.pair().shortName(), "supplier",
                            "Supplier renegotiation, " + Fmt.toFixed(buy.savingPct().doubleValue(), 0) + "% over target",
                            Fmt.round2(buy.annualSaving().doubleValue() / 12),
                            "/app/buy?item=" + f.pair().itemNumber()));
                }
            });
            if (f.demand() != null && "low".equals(f.demand().level())) {
                opportunities.add(new StoreOpportunity(f.pair().itemNumber(), f.pair().shortName(), "demand",
                        "Declining demand, review stock", Fmt.round2(Fmt.dv0(f.inventoryValue()) * 0.02),
                        "/app/products?item=" + f.pair().itemNumber()));
            }
            BigDecimal cover = f.weeksOfCover();
            if (cover != null && cover.doubleValue() > 16) {
                opportunities.add(new StoreOpportunity(f.pair().itemNumber(), f.pair().shortName(), "inventory",
                        "Overstocked, " + Fmt.toFixed(cover.doubleValue(), 0) + " weeks of cover",
                        Fmt.round2(Fmt.dv0(f.inventoryValue()) * 0.015),
                        "/app/sell/bulk?store=" + storeCode + "&preset=overstock"));
            }
        }
        opportunities.sort((a, b) -> Double.compare(b.impact(), a.impact()));
        products.sort(Comparator.comparingInt(ProductRow::score).reversed());

        Double marginPct = costedRevenue.signum() > 0
                ? Fmt.dv(PricingMath.pct(costedRevenue.subtract(cogs), costedRevenue)) : null;
        Integer pricingAccuracyPct = priceableCount > 0 ? (int) Math.round(accurate * 100.0 / priceableCount) : null;
        Double avgCover = coverCount > 0 ? coverSum.doubleValue() / coverCount : null;
        String inventoryRisk = !anyInventory ? null : avgCover > 13 ? "High" : avgCover > 9 ? "Medium" : "Low";
        double demandPct = driftCount > 0 ? Fmt.round1(driftSum.doubleValue() / driftCount) : 0;

        DealSummaries.Adoption storeAdoption = data.adoptionFor(noBranch ? null : storeCode);
        Integer adoptionPct = storeAdoption.followRatePct().map(v -> (int) Math.round(v)).orElse(null);

        HealthResult health = computeHealth(marginPct, pricingAccuracyPct, adoptionPct == null ? null
                : adoptionPct.doubleValue(), inventoryRisk);

        List<String> locked = new ArrayList<>();
        if (marginPct == null) {
            locked.add("margin");
        }
        if (!anyInventory) {
            locked.add("inventory");
        }
        if (adoptionPct == null) {
            locked.add("decisions");
        }

        String regionKey = noBranch ? UNASSIGNED : store.regionKey();
        String city = noBranch ? "No branch on file" : storeCity(store);
        String label = noBranch ? "No branch on file" : store.label();
        String state = noBranch || store.subdivisionCode() == null ? "" : store.subdivisionCode();

        return new StoreIntel(storeCode, label, city, state, regionKey, regionLabel(regionKey), regionKey,
                Fmt.round2(revenue.doubleValue()), marginPct, pricingAccuracyPct, adoptionPct,
                anyInventory ? Fmt.dv(inventoryValue) : null, inventoryRisk, demandPct, adoptionPct,
                health.status(), health.label(), health.note(),
                opportunities.size() > 6 ? opportunities.subList(0, 6) : opportunities, products, locked);
    }

    static String storeCity(com.aatlas.history.Catalogue.StoreRef store) {
        String source = store.msaName() != null && !store.msaName().isBlank() ? store.msaName() : store.legalName();
        return source.split("-")[0].replace(" Branch", "").trim();
    }

    private static String regionLabel(String key) {
        return UNASSIGNED.equals(key) ? "Needs a region" : key;
    }

    private static IllegalStateException notFound(String storeCode) {
        return new IllegalStateException("Unknown store " + storeCode);
    }

    /** Every real branch, plus the synthetic {@code no-branch} row when there are unbranched sales. */
    List<StoreIntel> allStores(InsightsData data) {
        List<StoreIntel> out = new ArrayList<>();
        for (var store : data.stores()) {
            out.add(storeIntel(store.storeCode(), data));
        }
        if (!data.atStore(NO_BRANCH).isEmpty()) {
            out.add(storeIntel(NO_BRANCH, data));
        }
        return out;
    }

    RegionIntel regionIntel(String key, InsightsData data) {
        boolean unassigned = UNASSIGNED.equals(key);
        List<String> storeIds = new ArrayList<>();
        for (var store : data.stores()) {
            boolean inRegion = unassigned ? UNASSIGNED.equals(store.regionKey()) : key.equals(store.regionKey());
            if (inRegion) {
                storeIds.add(store.storeCode());
            }
        }
        if (unassigned && !data.atStore(NO_BRANCH).isEmpty()) {
            storeIds.add(NO_BRANCH);
        }
        List<StoreIntel> stores = storeIds.stream().map(id -> storeIntel(id, data)).toList();

        SalesHistory.GroupStats group = InsightsData.group(data.byRegion(), key).orElse(null);
        BigDecimal revenue = group == null ? BigDecimal.ZERO : orZero(group.current().revenue());
        BigDecimal growthPct = group == null ? null
                : PricingMath.pct(revenue.subtract(orZero(group.prior().revenue())), orZero(group.prior().revenue()));
        BigDecimal marginPct = group == null ? null : group.current().grossMarginPct();
        BigDecimal avgSellingPrice = group != null && group.current().units() != null
                && group.current().units().signum() > 0 ? PricingMath.div(revenue, group.current().units()) : null;
        BigDecimal priorAvgPrice = group == null ? null : group.prior().avgPrice();
        BigDecimal priceTrendPct = group != null && group.current().avgPrice() != null && priorAvgPrice != null
                ? PricingMath.pct(group.current().avgPrice().subtract(priorAvgPrice), priorAvgPrice) : null;

        double demandPct = growthPct == null ? 0 : growthPct.doubleValue();

        Map<String, BigDecimal> categoryRevenue = new LinkedHashMap<>();
        String fastestItem = null;
        String fastestName = null;
        double fastestPct = Double.NEGATIVE_INFINITY;
        java.util.Set<String> raisePrices = new java.util.LinkedHashSet<>();
        java.util.Set<String> increaseInventory = new java.util.LinkedHashSet<>();
        java.util.Set<String> reviewSuppliers = new java.util.LinkedHashSet<>();

        for (String storeId : storeIds) {
            for (PairFacts f : data.atStore(storeId)) {
                BigDecimal itemRevenue = f.pair().w12() == null ? BigDecimal.ZERO : orZero(f.pair().w12().revenue());
                categoryRevenue.merge(f.pair().category(), itemRevenue, BigDecimal::add);
                if (f.demand() != null) {
                    double drift = PricingMath.driftPct90(f.commodityPct90(), f.demand().movePercent()).doubleValue();
                    if (drift > fastestPct) {
                        fastestPct = drift;
                        fastestItem = f.pair().itemNumber();
                        fastestName = f.pair().shortName();
                    }
                }
                if (!f.priceable()) {
                    continue;
                }
                BigDecimal upliftPct = f.upliftPct();
                if (upliftPct != null && upliftPct.doubleValue() > 2) {
                    raisePrices.add(f.pair().itemNumber());
                }
                BigDecimal cover = f.weeksOfCover();
                if (f.demand() != null && "high".equals(f.demand().level()) && cover != null && cover.doubleValue() < 5) {
                    increaseInventory.add(f.pair().itemNumber());
                }
                buyEngine.compute(f, data.today()).ifPresent(buy -> {
                    if (buy.savingPct() != null && buy.savingPct().doubleValue() > 4 && buy.incumbentSupplierName() != null) {
                        reviewSuppliers.add(buy.incumbentSupplierName());
                    }
                });
            }
        }
        String topCategory = categoryRevenue.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("Plumbing");
        java.util.Set<String> risks = stores.stream().map(StoreIntel::inventoryRisk)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        String inventoryRisk = risks.contains("High") ? "High" : risks.contains("Medium") ? "Medium" : "Low";

        int followed = 0;
        int total = 0;
        for (String storeId : storeIds) {
            DealSummaries.Adoption a = data.adoptionFor(NO_BRANCH.equals(storeId) ? null : storeId);
            followed += a.followed();
            total += a.total();
        }
        Integer conversionPct = total == 0 ? null : (int) Math.round(followed * 100.0 / total);

        String climateNote = group == null ? "No sales on file for this region yet."
                : "Revenue " + Fmt.compact(revenue.doubleValue()) + " in the last twelve months, "
                        + (growthPct == null ? "n/a" : Fmt.toFixed(growthPct.doubleValue(), 1) + "%")
                        + " on the prior year; margin "
                        + (marginPct == null ? "n/a" : Fmt.toFixed(marginPct.doubleValue(), 1) + "%") + ".";

        var region = data.regions().stream().filter(r -> r.key().equals(key)).findFirst().orElse(null);
        String label = unassigned ? "Needs a region" : region != null ? region.shortLabel() : key;
        String fullLabel = unassigned ? "Needs a region" : region != null ? region.label() : key;

        List<RegionAction> actions = List.of(
                new RegionAction("Increase inventory", increaseInventory.size(),
                        "/app/products?region=" + key + "&filter=strong"),
                new RegionAction("Adjust prices", raisePrices.size(), "/app/sell/bulk?region=" + key),
                new RegionAction("Review suppliers", reviewSuppliers.size(), "/app/buy/bulk?region=" + key));

        List<String> locked = new ArrayList<>();
        if (marginPct == null) {
            locked.add("margin");
        }

        return new RegionIntel(key, label, fullLabel, storeIds, stores, Fmt.round2(revenue.doubleValue()), demandPct,
                Fmt.dv(avgSellingPrice), Fmt.dv(marginPct), conversionPct,
                priceTrendPct == null ? 0 : Fmt.round1(priceTrendPct.doubleValue()), inventoryRisk, topCategory,
                fastestItem == null ? new FastestGrowing("", "—", 0) : new FastestGrowing(fastestItem, fastestName, Fmt.round1(fastestPct)),
                unassigned ? "Needs a region" : headlineFor(key, data), climateNote, actions,
                List.copyOf(raisePrices), List.copyOf(increaseInventory), List.copyOf(reviewSuppliers), locked);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String headlineFor(String key, InsightsData data) {
        List<RegionMetrics> metrics = new ArrayList<>();
        for (var region : data.regions()) {
            SalesHistory.GroupStats g = InsightsData.group(data.byRegion(), region.key()).orElse(null);
            if (g == null) {
                continue;
            }
            BigDecimal revenue = orZero(g.current().revenue());
            BigDecimal growth = PricingMath.pct(revenue.subtract(orZero(g.prior().revenue())), orZero(g.prior().revenue()));
            metrics.add(new RegionMetrics(region.key(), growth, g.current().grossMarginPct(), revenue));
        }
        return regionHeadlines(metrics).getOrDefault(key, "Steady");
    }

    /** Regions with at least one store - the tenant's real regions, never {@code unassigned}. */
    List<RegionIntel> allRegions(InsightsData data) {
        List<RegionIntel> out = new ArrayList<>();
        for (var region : data.regions()) {
            boolean hasStore = data.stores().stream().anyMatch(s -> region.key().equals(s.regionKey()));
            if (hasStore) {
                out.add(regionIntel(region.key(), data));
            }
        }
        return out;
    }
}
