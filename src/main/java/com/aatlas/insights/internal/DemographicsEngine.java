package com.aatlas.insights.internal;

import com.aatlas.history.Catalogue;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.PurchaseHistory.PoGroup;
import com.aatlas.history.Reference;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesHistory.GroupStats;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Suppliers;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec 3.9: who is buying, where, what they buy, and who the branch buys from - over
 * {@code SalesHistory.byCustomerSegment}/{@code byCategory}/{@code byStore} and {@code
 * PurchaseHistory.byOrigin}, for the requested period (30d/90d/12m). Segments and categories
 * are tenant-wide (the read layer's group queries have no store dimension); {@code region}/
 * {@code state}/{@code store} narrow which branches make up {@code places}, and {@code
 * segment}/{@code category} only mark which row is {@code selected} - the frontend already
 * reads the full mix and highlights the one it asked for.
 */
@Component
class DemographicsEngine {

    private final SalesHistory salesHistory;
    private final PurchaseHistory purchaseHistory;
    private final Reference reference;
    private final Suppliers suppliers;
    private final GeoEngine geoEngine;

    DemographicsEngine(SalesHistory salesHistory, PurchaseHistory purchaseHistory, Reference reference,
            Suppliers suppliers, GeoEngine geoEngine) {
        this.salesHistory = salesHistory;
        this.purchaseHistory = purchaseHistory;
        this.reference = reference;
        this.suppliers = suppliers;
        this.geoEngine = geoEngine;
    }

    record Filter(String region, String state, String storeId, String segment, String category, String period) {
    }

    record SegmentRow(String segment, String label, double revenue, double sharePct, Double marginPct,
            Double growthPct, Double avgOrder, int customers, boolean selected) {
    }

    record CategoryRow(String category, double revenue, double sharePct, Double marginPct, Double demandPct,
            double units, boolean selected) {
    }

    record PlaceRow(String storeId, String label, String city, String state, String regionKey, String regionLabel,
            double revenue, double sharePct, Double growthPct, Double marginPct, String topSegment,
            String topCategory, String health) {
    }

    record OriginRow(String country, double spend, double sharePct, int suppliers, Integer avgLeadDays,
            Double avgOtifPct, double dutyPct, String mode) {
    }

    record Action(String label, String href) {
    }

    record Demographics(
            String scopeLabel, String periodLabel, double totalRevenue, int customers,
            List<SegmentRow> segments, List<CategoryRow> categories, List<PlaceRow> places, List<OriginRow> origins,
            SegmentRow topSegment, SegmentRow fastestSegment, CategoryRow topCategory,
            String headline, List<Action> actions) {
    }

    private static final Map<String, String> SEGMENT_LABEL = Map.of(
            "contractor", "Contractors", "institutional", "Institutional accounts",
            "industrial", "Industrial accounts", "walk-in", "Walk-in trade", "unassigned", "Unassigned");

    private static Window windowFor(String period, LocalDate today) {
        return switch (period == null ? "12m" : period) {
            case "30d" -> Window.trailingDays(today, 30);
            case "90d" -> Window.trailingDays(today, 90);
            default -> Window.trailingMonths(today, 12);
        };
    }

    private static String periodLabel(String period) {
        return switch (period == null ? "12m" : period) {
            case "30d" -> "Last 30 days";
            case "90d" -> "Last 90 days";
            default -> "Last 12 months";
        };
    }

    Demographics compute(Filter f, InsightsData data) {
        LocalDate today = data.today();
        Window window = windowFor(f.period(), today);

        List<Catalogue.StoreRef> scopedStores = scopedStores(f, data);
        boolean includeNoBranch = "all".equals(f.region()) && "all".equals(f.state()) && f.storeId() == null
                && !data.atStore(GeoEngine.NO_BRANCH).isEmpty();

        List<GroupStats> segGroups = salesHistory.byCustomerSegment(window);
        List<GroupStats> catGroups = salesHistory.byCategory(window);
        List<GroupStats> storeGroups = salesHistory.byStore(window);
        List<PoGroup> originGroups = purchaseHistory.byOrigin(window);

        BigDecimal totalRevenue = BigDecimal.ZERO;
        for (GroupStats g : segGroups) {
            totalRevenue = totalRevenue.add(orZero(g.current().revenue()));
        }
        BigDecimal totalRevenueSafe = totalRevenue.signum() == 0 ? BigDecimal.ONE : totalRevenue;

        List<SegmentRow> segments = new ArrayList<>();
        int customers = 0;
        for (GroupStats g : segGroups) {
            SalesStats cur = g.current();
            customers += cur.customers();
            BigDecimal growth = growthPct(g);
            segments.add(new SegmentRow(g.key(), SEGMENT_LABEL.getOrDefault(g.key().toLowerCase(java.util.Locale.ROOT), g.label()),
                    round2(cur.revenue()), pctOf(cur.revenue(), totalRevenueSafe),
                    Fmt.dv(cur.grossMarginPct()), Fmt.dv(growth),
                    cur.customers() > 0 ? Fmt.dv(PricingMath.div(orZero(cur.revenue()), BigDecimal.valueOf(cur.customers()))) : null,
                    cur.customers(), g.key().equalsIgnoreCase(f.segment())));
        }

        List<CategoryRow> categories = new ArrayList<>();
        for (GroupStats g : catGroups) {
            SalesStats cur = g.current();
            BigDecimal growth = growthPct(g);
            categories.add(new CategoryRow(g.key(), round2(cur.revenue()), pctOf(cur.revenue(), totalRevenueSafe),
                    Fmt.dv(cur.grossMarginPct()), Fmt.dv(growth), cur.units() == null ? 0 : cur.units().doubleValue(),
                    g.key().equalsIgnoreCase(f.category())));
        }

        Map<String, BigDecimal> categoryRevenueByStore = new LinkedHashMap<>();
        List<PlaceRow> places = new ArrayList<>();
        for (Catalogue.StoreRef store : scopedStores) {
            places.add(placeRow(store.storeCode(), storeGroups, data));
        }
        if (includeNoBranch) {
            places.add(placeRow(GeoEngine.NO_BRANCH, storeGroups, data));
        }
        BigDecimal placesRevenue = places.stream().map(p -> BigDecimal.valueOf(p.revenue()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal placesRevenueSafe = placesRevenue.signum() == 0 ? BigDecimal.ONE : placesRevenue;
        List<PlaceRow> placesWithShare = places.stream()
                .map(p -> new PlaceRow(p.storeId(), p.label(), p.city(), p.state(), p.regionKey(), p.regionLabel(),
                        p.revenue(), Fmt.round1(p.revenue() / placesRevenueSafe.doubleValue() * 100), p.growthPct(),
                        p.marginPct(), p.topSegment(), p.topCategory(), p.health()))
                .sorted((a, b) -> Double.compare(b.revenue(), a.revenue()))
                .toList();

        BigDecimal totalSpend = BigDecimal.ZERO;
        for (PoGroup g : originGroups) {
            totalSpend = totalSpend.add(orZero(g.current().spend()));
        }
        BigDecimal totalSpendSafe = totalSpend.signum() == 0 ? BigDecimal.ONE : totalSpend;
        List<OriginRow> origins = originGroups.stream().map(g -> {
            PurchaseHistory.PoStats cur = g.current();
            Reference.Origin origin = reference.origin(g.key());
            long supplierCount = suppliers.panel().stream()
                    .filter(s -> g.key().equalsIgnoreCase(s.country())).count();
            return new OriginRow(g.key(), round2(cur.spend()), pctOf(cur.spend(), totalSpendSafe), (int) supplierCount,
                    cur.avgLeadDays() == null ? null : (int) Math.round(cur.avgLeadDays().doubleValue()),
                    Fmt.dv(cur.otifPct()), origin.dutyPct() == null ? 0 : origin.dutyPct().doubleValue(), origin.mode());
        }).sorted((a, b) -> Double.compare(b.spend(), a.spend())).toList();

        List<SegmentRow> byRevenue = segments.stream().filter(s -> s.revenue() > 0)
                .sorted((a, b) -> Double.compare(b.revenue(), a.revenue())).toList();
        SegmentRow topSegment = byRevenue.isEmpty() ? null : byRevenue.get(0);
        SegmentRow fastestSegment = segments.stream().filter(s -> s.growthPct() != null)
                .max(java.util.Comparator.comparingDouble(SegmentRow::growthPct)).orElse(topSegment);
        CategoryRow topCategory = categories.stream().filter(c -> c.revenue() > 0)
                .max(java.util.Comparator.comparingDouble(CategoryRow::revenue)).orElse(null);

        String scopeLabel = scopeLabel(f, data, scopedStores);
        String where = "All regions".equals(scopeLabel) ? "across the network" : "in " + scopeLabel;
        String headline = topSegment == null ? "" : topSegment.label() + " drive "
                + Fmt.toFixed(topSegment.sharePct(), 0) + "% of revenue " + where + ".";

        List<Action> actions = new ArrayList<>();
        if (topCategory != null) {
            actions.add(new Action("Review pricing for " + topCategory.category() + ", the top category " + where,
                    "/app/products?category=" + topCategory.category()));
        }
        if (fastestSegment != null && fastestSegment != topSegment) {
            actions.add(new Action("Stock the lines " + fastestSegment.label().toLowerCase(java.util.Locale.ROOT)
                    + " buy: fastest-growing segment " + where, "/app/stores"));
        }

        return new Demographics(scopeLabel, periodLabel(f.period()), round2(totalRevenue), customers,
                segments, categories, placesWithShare, origins, topSegment, fastestSegment, topCategory,
                headline, actions);
    }

    private PlaceRow placeRow(String storeCode, List<GroupStats> storeGroups, InsightsData data) {
        GroupStats g = InsightsData.group(storeGroups, storeCode).orElse(null);
        boolean noBranch = GeoEngine.NO_BRANCH.equals(storeCode);
        var store = noBranch ? null : data.store(storeCode).orElse(null);
        GeoEngine.StoreIntel intel = geoEngine.storeIntel(storeCode, data);

        Map<String, BigDecimal> categoryRevenue = new LinkedHashMap<>();
        for (PairFacts pf : data.atStore(storeCode)) {
            BigDecimal rev = pf.pair().w12() == null ? BigDecimal.ZERO : orZero(pf.pair().w12().revenue());
            categoryRevenue.merge(pf.pair().category(), rev, BigDecimal::add);
        }
        String topCategory = categoryRevenue.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);

        BigDecimal revenue = g == null ? BigDecimal.ZERO : orZero(g.current().revenue());
        BigDecimal growth = g == null ? null : growthPct(g);

        return new PlaceRow(storeCode, intel.label(), intel.city(), intel.state(), intel.regionKey(),
                intel.regionLabel(), round2(revenue), 0, Fmt.dv(growth), intel.marginPct(), null, topCategory,
                intel.health());
    }

    private List<Catalogue.StoreRef> scopedStores(Filter f, InsightsData data) {
        List<Catalogue.StoreRef> stores = data.stores();
        if (f.storeId() != null) {
            return stores.stream().filter(s -> s.storeCode().equals(f.storeId())).toList();
        }
        List<Catalogue.StoreRef> byRegion = "all".equals(f.region()) ? stores
                : stores.stream().filter(s -> f.region().equals(s.regionKey())).toList();
        if (!"all".equals(f.state())) {
            byRegion = byRegion.stream().filter(s -> f.state().equals(s.subdivisionCode())).toList();
        }
        return byRegion.isEmpty() ? stores : byRegion;
    }

    private String scopeLabel(Filter f, InsightsData data, List<Catalogue.StoreRef> scopedStores) {
        if (f.storeId() != null && scopedStores.size() == 1) {
            return scopedStores.get(0).label();
        }
        if (!"all".equals(f.state())) {
            return f.state() + ("all".equals(f.region()) ? "" : ", " + f.region());
        }
        if ("all".equals(f.region())) {
            return "All regions";
        }
        var region = data.regions().stream().filter(r -> r.key().equals(f.region())).findFirst();
        return region.map(Catalogue.RegionRef::shortLabel).orElse(f.region());
    }

    private static BigDecimal growthPct(GroupStats g) {
        BigDecimal cur = orZero(g.current().revenue());
        BigDecimal prior = orZero(g.prior().revenue());
        return PricingMath.pct(cur.subtract(prior), prior);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static double round2(BigDecimal v) {
        return v == null ? 0 : v.setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static double pctOf(BigDecimal v, BigDecimal total) {
        return Fmt.round1(orZero(v).doubleValue() / total.doubleValue() * 100);
    }
}
