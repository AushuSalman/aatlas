package com.aatlas.insights.internal;

import com.aatlas.insights.internal.BuyEngine.BuyRecommendation;
import com.aatlas.insights.internal.BuyEngine.SupplierQuote;
import com.aatlas.insights.internal.GeoEngine.Climate;
import com.aatlas.insights.internal.GeoEngine.RegionIntel;
import com.aatlas.insights.internal.GeoEngine.StoreIntel;
import com.aatlas.insights.internal.PricingEngine.PricingModel;
import com.aatlas.insights.internal.SellEngine.SellSummary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A port of {@code intel/demographics.ts}'s {@code getDemographics}: who is buying, where,
 * what they buy, and who the branch buys from - filtered by region, state, branch, customer
 * segment, product category and period. Category and place figures come from the pricing
 * engine (price x volume per line at each branch, exactly as {@code geo.ts} sums them);
 * customer-segment shares are seeded per region with a point of view, plus per-branch
 * variation, matching the TypeScript's own constants verbatim.
 */
final class DemographicsEngine {

    private DemographicsEngine() {
    }

    static final List<String> SEGMENTS = List.of("Contractor", "Institutional", "Industrial", "Walk-in");
    static final Map<String, String> SEGMENT_LABEL = Map.of(
            "Contractor", "Contractors", "Institutional", "Institutional accounts",
            "Industrial", "Industrial accounts", "Walk-in", "Walk-in trade");
    static final List<String> CATEGORIES = List.of("Plumbing", "HVAC", "Water heating", "Fixtures");
    static final List<PeriodDef> PERIODS = List.of(
            new PeriodDef("30d", "Last 30 days", 1.0 / 12),
            new PeriodDef("90d", "Last 90 days", 1.0 / 4),
            new PeriodDef("12m", "Last 12 months", 1.0));

    record PeriodDef(String key, String label, double factor) {
    }

    record Filter(String region, String state, String storeId, String segment, String category, String period) {
    }

    record SegmentRow(String segment, String label, double revenue, double sharePct, double marginPct,
            double growthPct, int avgOrder, int customers, boolean selected) {
    }

    record CategoryRow(String category, double revenue, double sharePct, double marginPct, double demandPct,
            int units, boolean selected) {
    }

    record PlaceRow(String storeId, String label, String city, String state, String regionKey, String regionLabel,
            double revenue, double sharePct, double growthPct, double marginPct, String topSegment,
            String topCategory, String health) {
    }

    record OriginRow(String country, double spend, double sharePct, int suppliers, int avgLeadDays,
            double avgOtifPct, double dutyPct, String mode) {
    }

    record Action(String label, String href) {
    }

    record Demographics(
            String scopeLabel, String periodLabel, double totalRevenue, int customers,
            List<SegmentRow> segments, List<CategoryRow> categories, List<PlaceRow> places, List<OriginRow> origins,
            SegmentRow topSegment, SegmentRow fastestSegment, CategoryRow topCategory,
            String headline, List<Action> actions) {
    }

    private static final Map<String, Map<String, Double>> SEGMENT_BASE = Map.of(
            "south", Map.of("Contractor", 54.0, "Industrial", 20.0, "Institutional", 16.0, "Walk-in", 10.0),
            "west", Map.of("Contractor", 46.0, "Industrial", 24.0, "Institutional", 18.0, "Walk-in", 12.0),
            "north", Map.of("Contractor", 50.0, "Industrial", 14.0, "Institutional", 26.0, "Walk-in", 10.0),
            "east", Map.of("Contractor", 44.0, "Industrial", 20.0, "Institutional", 28.0, "Walk-in", 8.0));
    private static final Map<String, Double> SEGMENT_MARGIN =
            Map.of("Contractor", 33.0, "Institutional", 29.0, "Industrial", 37.0, "Walk-in", 44.0);
    private static final Map<String, Double> SEGMENT_GROWTH =
            Map.of("Contractor", 3.0, "Institutional", -2.0, "Industrial", 1.0, "Walk-in", -4.0);
    private static final Map<String, Double> SEGMENT_ORDER =
            Map.of("Contractor", 1850.0, "Institutional", 4200.0, "Industrial", 3100.0, "Walk-in", 140.0);
    private static final Map<String, Double> SEGMENT_ORDERS_PER_YEAR =
            Map.of("Contractor", 26.0, "Institutional", 9.0, "Industrial", 14.0, "Walk-in", 5.0);

    private static Map<String, Double> segmentShares(String storeId, String regionKey) {
        Map<String, Double> base = SEGMENT_BASE.get(regionKey);
        double[] raw = new double[SEGMENTS.size()];
        double sum = 0;
        for (int i = 0; i < SEGMENTS.size(); i++) {
            String s = SEGMENTS.get(i);
            raw[i] = Math.max(3, base.get(s) + (GeoEngine.storeSeed(storeId, "seg-" + s) - 0.5) * 8);
            sum += raw[i];
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < SEGMENTS.size(); i++) {
            out.put(SEGMENTS.get(i), (raw[i] / sum) * 100);
        }
        return out;
    }

    private record CategoryMixRow(double share, double margin, double demandPct, double units) {
    }

    private static Map<String, CategoryMixRow> categoryMix(String storeId, CatalogSnapshot snapshot) {
        Map<String, double[]> acc = new LinkedHashMap<>(); // rev, cogs, demand, n, units
        for (String c : CATEGORIES) {
            acc.put(c, new double[5]);
        }
        for (ProductRef p : snapshot.sellableProducts()) {
            if (!snapshot.priceable(p.itemNumber(), storeId)) {
                continue;
            }
            PricingModel m = PricingEngine.compute(p.itemNumber(), storeId, snapshot);
            SellSummary intel = SellEngine.compute(p.itemNumber(), storeId, snapshot, m);
            double[] a = acc.get(p.category());
            if (a == null) {
                // A category this mix has no column for - in practice 'uncategorised', which
                // is what ingest writes for a product invented by a CSV import (it declines to
                // guess a category, deliberately). The accumulator is pre-sized to CATEGORIES,
                // so this used to read null and throw, and one imported SKU was enough to make
                // GET /insights/demographics 500 for the whole tenant.
                //
                // Skipped rather than given a column of its own: the shares below are what the
                // frontend renders against four fixed categories, and a fifth would change that
                // contract. The consequence is that an uncategorised line is absent from the
                // mix until someone categorises it, which is the honest reading of "we do not
                // know what this is" - better than inventing a category for it.
                continue;
            }
            a[0] += intel.currentPrice() * intel.annualUnits();
            a[1] += intel.cost() * intel.annualUnits();
            a[2] += (m.demand() != null ? m.demand().movePercent() : 0) * 3;
            a[3] += 1;
            a[4] += intel.annualUnits();
        }
        double total = 0;
        for (String c : CATEGORIES) {
            total += acc.get(c)[0];
        }
        if (total == 0) {
            total = 1;
        }
        Map<String, CategoryMixRow> out = new LinkedHashMap<>();
        for (String c : CATEGORIES) {
            double[] a = acc.get(c);
            out.put(c, new CategoryMixRow(
                    (a[0] / total) * 100,
                    a[0] > 0 ? ((a[0] - a[1]) / a[0]) * 100 : 0,
                    a[3] > 0 ? a[2] / a[3] : 0,
                    a[4]));
        }
        return out;
    }

    static List<String> statesInScope(String region, CatalogSnapshot snapshot) {
        List<StoreRef> stores = snapshot.allStoresOrdered();
        Set<String> ids = "all".equals(region)
                ? stores.stream().map(StoreRef::storeCode).collect(java.util.stream.Collectors.toSet())
                : Set.copyOf(storesInMarketRegion(region, snapshot));
        return stores.stream()
                .filter(s -> ids.contains(s.storeCode()))
                .map(StoreRef::state)
                .filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.toCollection(() -> new java.util.TreeSet<String>()))
                .stream().toList();
    }

    private static List<String> storesInMarketRegion(String key, CatalogSnapshot snapshot) {
        return snapshot.allStoresOrdered().stream()
                .filter(s -> snapshot.marketRegionForState(s.state()).key().equals(key))
                .map(StoreRef::storeCode)
                .toList();
    }

    static Demographics compute(Filter f, CatalogSnapshot snapshot, DealsIndex deals) {
        PeriodDef period = PERIODS.stream().filter(p -> p.key().equals(f.period())).findFirst()
                .orElse(PERIODS.get(2));

        List<String> storeIds = "all".equals(f.region())
                ? snapshot.allStoresOrdered().stream().map(StoreRef::storeCode).toList()
                : storesInMarketRegion(f.region(), snapshot);
        if (!"all".equals(f.state())) {
            storeIds = storeIds.stream()
                    .filter(id -> f.state().equals(snapshot.store(id).map(StoreRef::state).orElse(null)))
                    .toList();
        }
        if (f.storeId() != null && storeIds.contains(f.storeId())) {
            storeIds = List.of(f.storeId());
        }
        if (storeIds.isEmpty()) {
            storeIds = snapshot.allStoresOrdered().stream().map(StoreRef::storeCode).toList();
        }

        String scopeLabel;
        if (f.storeId() != null && storeIds.size() == 1) {
            scopeLabel = GeoEngine.storeLabel(storeIds.get(0), snapshot);
        } else if (!"all".equals(f.state())) {
            String stateName = subdivisionName(f.state(), snapshot);
            String suffix = "all".equals(f.region()) ? "" : ", " + snapshot.marketRegion(f.region()).shortLabel();
            scopeLabel = stateName + suffix;
        } else if ("all".equals(f.region())) {
            scopeLabel = "All regions";
        } else {
            scopeLabel = snapshot.marketRegion(f.region()).shortLabel() + " region";
        }

        Map<String, double[]> segAcc = new LinkedHashMap<>(); // revenue, margin, growth, w, customers
        Map<String, double[]> catAcc = new LinkedHashMap<>(); // revenue, margin, demand, w, units
        for (String s : SEGMENTS) {
            segAcc.put(s, new double[5]);
        }
        for (String c : CATEGORIES) {
            catAcc.put(c, new double[5]);
        }
        List<PlaceRow> places = new ArrayList<>();
        Map<String, double[]> originAcc = new LinkedHashMap<>(); // spend, lead*, otif*, w
        Map<String, Set<String>> originSuppliers = new LinkedHashMap<>();

        for (String storeId : storeIds) {
            StoreRef store = snapshot.store(storeId).orElseThrow();
            var region = snapshot.marketRegionForState(store.state());
            Climate climate = GeoEngine.regionClimate(region.key());
            StoreIntel storeIntel = GeoEngine.getStoreIntel(storeId, snapshot, deals);
            double seasonal = "12m".equals(f.period())
                    ? 1
                    : 1 + (GeoEngine.storeSeed(storeId, "season-" + f.period()) - 0.5) * ("30d".equals(f.period()) ? 0.18 : 0.08);
            Map<String, Double> shares = segmentShares(storeId, region.key());
            Map<String, CategoryMixRow> cats = categoryMix(storeId, snapshot);

            double segShare = "all".equals(f.segment()) ? 1 : shares.get(f.segment()) / 100;
            double catShare = "all".equals(f.category()) ? 1 : cats.get(f.category()).share() / 100;
            double storeRevenue = storeIntel.revenue() * period.factor() * seasonal * segShare * catShare;
            double level = 1 + (climate.marginAdj() / 100);

            String topSegment = "Contractor";
            double topSegmentShare = -1;
            for (String s : SEGMENTS) {
                double revenue = storeRevenue * ("all".equals(f.segment()) ? shares.get(s) / 100 : s.equals(f.segment()) ? 1 : 0);
                double growth = climate.demandPct() + SEGMENT_GROWTH.get(s) + (GeoEngine.storeSeed(storeId, "grow-" + s) - 0.5) * 6;
                double margin = SEGMENT_MARGIN.get(s) + climate.marginAdj() + (GeoEngine.storeSeed(storeId, "mar-" + s) - 0.5) * 4;
                double orders = revenue / (SEGMENT_ORDER.get(s) * level);
                double customers = orders / (SEGMENT_ORDERS_PER_YEAR.get(s) * period.factor());
                double[] a = segAcc.get(s);
                a[0] += revenue;
                a[1] += margin * revenue;
                a[2] += growth * revenue;
                a[3] += revenue;
                a[4] += customers;
                if (shares.get(s) > topSegmentShare) {
                    topSegmentShare = shares.get(s);
                    topSegment = s;
                }
            }

            String topCategory = "Plumbing";
            double topCategoryShare = -1;
            for (String c : CATEGORIES) {
                CategoryMixRow mix = cats.get(c);
                double revenue = storeRevenue * ("all".equals(f.category()) ? mix.share() / 100 : c.equals(f.category()) ? 1 : 0);
                double[] a = catAcc.get(c);
                a[0] += revenue;
                a[1] += mix.margin() * revenue;
                a[2] += (climate.demandPct() + mix.demandPct()) * revenue;
                a[3] += revenue;
                a[4] += mix.units() * period.factor() * segShare * ("all".equals(f.category()) || c.equals(f.category()) ? 1 : 0);
                if (mix.share() > topCategoryShare) {
                    topCategoryShare = mix.share();
                    topCategory = c;
                }
            }

            places.add(new PlaceRow(storeId, storeIntel.label(), GeoEngine.storeCity(store),
                    store.state() == null ? "" : store.state(), region.key(), region.shortLabel(),
                    storeRevenue, 0, Fmt.round1(storeIntel.demandPct()), storeIntel.marginPct(),
                    topSegment, topCategory, storeIntel.health()));

            int regionStoreCount = Math.max(1, storesInMarketRegion(region.key(), snapshot).size());
            for (ProductRef p : snapshot.sellableProducts()) {
                if (!"all".equals(f.category()) && !p.category().equals(f.category())) {
                    continue;
                }
                if (!snapshot.priceable(p.itemNumber(), storeId)) {
                    continue;
                }
                PricingModel m = PricingEngine.compute(p.itemNumber(), storeId, snapshot);
                if (snapshot.suppliers().isEmpty()) {
                    // Suppliers seed asynchronously right after a data source connects; treat
                    // "not seeded yet" the same as "no incumbent found" below, not a crash.
                    continue;
                }
                BuyRecommendation buy = BuyEngine.compute(p.itemNumber(), storeId, snapshot);
                SupplierRef sup = snapshot.suppliers().stream()
                        .filter(s -> s.id().equals(buy.incumbentSupplierId())).findFirst().orElse(null);
                if (sup == null) {
                    continue;
                }
                SupplierQuote q = buy.quotes().stream().filter(SupplierQuote::isIncumbent).findFirst().orElse(null);
                double annualVolume = annualVolumeFor(p.itemNumber(), region.key(), m.cost());
                double spend = (buy.incumbentCost() * annualVolume * period.factor() * segShare) / regionStoreCount;
                // slot 0 = lead-days * spend, slot 1 = otif * spend, slot 2 = spend (the weight both ride on).
                double[] a = originAcc.computeIfAbsent(sup.country(), k -> new double[3]);
                Set<String> supSet = originSuppliers.computeIfAbsent(sup.country(), k -> new LinkedHashSet<>());
                supSet.add(sup.id());
                int leadDays = q != null ? q.totalLeadDays() : sup.leadTimeDays();
                a[0] += leadDays * spend;
                a[1] += sup.otifPct() * spend;
                a[2] += spend;
            }
        }

        double totalRevenue = places.stream().mapToDouble(PlaceRow::revenue).sum();
        if (totalRevenue == 0) {
            totalRevenue = 1;
        }
        double totalRevenueFinal = totalRevenue;
        List<PlaceRow> placesWithShare = places.stream()
                .map(p -> new PlaceRow(p.storeId(), p.label(), p.city(), p.state(), p.regionKey(), p.regionLabel(),
                        p.revenue(), Fmt.round1((p.revenue() / totalRevenueFinal) * 100), p.growthPct(),
                        p.marginPct(), p.topSegment(), p.topCategory(), p.health()))
                .sorted((a, b) -> Double.compare(b.revenue(), a.revenue()))
                .toList();

        List<SegmentRow> segments = new ArrayList<>();
        for (String s : SEGMENTS) {
            double[] a = segAcc.get(s);
            double revenue = Fmt.round2(a[0]);
            if (revenue <= 0 && !"all".equals(f.segment())) {
                continue;
            }
            segments.add(new SegmentRow(s, SEGMENT_LABEL.get(s), revenue,
                    Fmt.round1((a[0] / totalRevenueFinal) * 100),
                    a[3] != 0 ? Fmt.round1(a[1] / a[3]) : 0,
                    a[3] != 0 ? Fmt.round1(a[2] / a[3]) : 0,
                    (int) Math.round((double) SEGMENT_ORDER.get(s)), (int) Math.round(a[4]), f.segment().equals(s)));
        }

        List<CategoryRow> categories = new ArrayList<>();
        for (String c : CATEGORIES) {
            double[] a = catAcc.get(c);
            double revenue = Fmt.round2(a[0]);
            if (revenue <= 0 && !"all".equals(f.category())) {
                continue;
            }
            categories.add(new CategoryRow(c, revenue, Fmt.round1((a[0] / totalRevenueFinal) * 100),
                    a[3] != 0 ? Fmt.round1(a[1] / a[3]) : 0, a[3] != 0 ? Fmt.round1(a[2] / a[3]) : 0,
                    (int) Math.round(a[4]), f.category().equals(c)));
        }

        double totalSpend = originAcc.values().stream().mapToDouble(a -> a[2]).sum();
        if (totalSpend == 0) {
            totalSpend = 1;
        }
        double totalSpendFinal = totalSpend;
        List<OriginRow> origins = originAcc.entrySet().stream()
                .map(e -> {
                    String country = e.getKey();
                    double[] a = e.getValue();
                    double spend = a[2];
                    var ref = snapshot.logisticsOrigins().get(country);
                    return new OriginRow(country, Fmt.round2(spend), Fmt.round1((spend / totalSpendFinal) * 100),
                            originSuppliers.get(country).size(),
                            a[2] != 0 ? (int) Math.round(a[0] / a[2]) : 0,
                            a[2] != 0 ? Fmt.round1(a[1] / a[2]) : 0,
                            ref != null ? ref.dutyPct().doubleValue() : 0,
                            ref != null ? ref.mode() : "ocean");
                })
                .sorted((a, b) -> Double.compare(b.spend(), a.spend()))
                .toList();

        List<SegmentRow> byRevenue = segments.stream()
                .sorted((a, b) -> Double.compare(b.revenue(), a.revenue())).toList();
        SegmentRow topSegment = byRevenue.isEmpty() ? null : byRevenue.get(0);
        SegmentRow fastestSegment = segments.stream()
                .sorted((a, b) -> Double.compare(b.growthPct(), a.growthPct())).findFirst().orElse(topSegment);
        CategoryRow topCategory = categories.stream()
                .sorted((a, b) -> Double.compare(b.revenue(), a.revenue())).findFirst().orElse(null);
        int customers = (int) Math.round(segments.stream().mapToDouble(SegmentRow::customers).sum());

        String where = "All regions".equals(scopeLabel) ? "across the network" : "in " + scopeLabel;
        String headline = "";
        if (topSegment != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(topSegment.label()).append(" drive ").append(Fmt.toFixed(topSegment.sharePct(), 0))
                    .append("% of revenue ").append(where).append(", growing ")
                    .append(topSegment.growthPct() >= 0 ? "+" : "").append(Fmt.toFixed(topSegment.growthPct(), 0))
                    .append("%. ");
            if (fastestSegment != null && !fastestSegment.segment().equals(topSegment.segment())
                    && fastestSegment.growthPct() - topSegment.growthPct() >= 1) {
                sb.append(fastestSegment.label()).append(" are growing fastest at ")
                        .append(fastestSegment.growthPct() >= 0 ? "+" : "").append(Fmt.toFixed(fastestSegment.growthPct(), 0))
                        .append("%.");
            }
            headline = sb.toString().trim();
        }

        List<String> regionKeys = "all".equals(f.region())
                ? snapshot.marketRegions().stream().map(MarketRegionRef::key).toList()
                : List.of(f.region());
        Set<String> inv = new LinkedHashSet<>();
        Set<String> pricesUp = new LinkedHashSet<>();
        Set<String> sups = new LinkedHashSet<>();
        for (String k : regionKeys) {
            RegionIntel r = GeoEngine.getRegionIntel(k, snapshot, deals);
            inv.addAll(r.increaseInventory());
            pricesUp.addAll(r.raisePrices());
            sups.addAll(r.reviewSuppliers());
        }
        String regionQs = "all".equals(f.region()) ? "" : "?region=" + f.region();
        String bulkStore = storeIds.size() == 1 ? "?store=" + storeIds.get(0)
                : "all".equals(f.region()) ? "" : "?region=" + f.region();
        List<Action> actions = new ArrayList<>();
        if (!inv.isEmpty()) {
            actions.add(new Action("Increase inventory for " + inv.size() + " " + (inv.size() == 1 ? "product" : "products")
                    + " where demand is rising",
                    "/app/products" + (!regionQs.isEmpty() ? regionQs + "&filter=strong" : "?filter=strong")));
        }
        if (!pricesUp.isEmpty()) {
            actions.add(new Action("Adjust prices for " + pricesUp.size() + " " + (pricesUp.size() == 1 ? "product" : "products")
                    + " sitting under the market", "/app/sell/bulk" + bulkStore));
        }
        if (!sups.isEmpty()) {
            actions.add(new Action("Review " + sups.size() + " " + (sups.size() == 1 ? "supplier" : "suppliers")
                    + " priced above market", "/app/buy/bulk" + regionQs));
        }
        SegmentRow highestMargin = segments.stream()
                .sorted((a, b) -> Double.compare(b.marginPct(), a.marginPct())).findFirst().orElse(null);
        if (highestMargin != null && "Walk-in".equals(highestMargin.segment())) {
            actions.add(new Action("Protect counter pricing: walk-in trade earns " + Fmt.toFixed(highestMargin.marginPct(), 0) + "% margin",
                    "/app/products" + regionQs));
        } else if (fastestSegment != null) {
            actions.add(new Action("Stock the lines " + fastestSegment.label().toLowerCase() + " buy: fastest-growing segment " + where,
                    "/app/stores" + regionQs));
        }
        List<Action> actionsFinal = actions.size() > 4 ? actions.subList(0, 4) : actions;

        return new Demographics(scopeLabel, period.label(), Fmt.round2(totalRevenueFinal), customers,
                segments, categories, placesWithShare, origins, topSegment, fastestSegment, topCategory,
                headline, actionsFinal);
    }

    static double annualVolumeFor(String itemNumber, String regionKey, double cost) {
        String key = "avol:" + itemNumber + ":" + regionKey;
        if (cost < 15) {
            return com.aatlas.common.seed.Seeded.randInt(key, "v", 9000, 62000);
        }
        if (cost < 200) {
            return com.aatlas.common.seed.Seeded.randInt(key, "v", 1200, 11000);
        }
        return com.aatlas.common.seed.Seeded.randInt(key, "v", 60, 520);
    }

    private static String subdivisionName(String code, CatalogSnapshot snapshot) {
        return snapshot.subdivisionNames().getOrDefault(code, code);
    }
}
