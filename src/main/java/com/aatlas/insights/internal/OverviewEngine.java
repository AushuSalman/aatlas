package com.aatlas.insights.internal;

import com.aatlas.common.seed.Seeded;
import com.aatlas.insights.internal.BuyEngine.BuyRecommendation;
import com.aatlas.insights.internal.CrossTrackStandIns.DecisionView;
import com.aatlas.insights.internal.CrossTrackStandIns.ProcurementImpactReader;
import com.aatlas.insights.internal.CrossTrackStandIns.RecentDecisionsReader;
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
import org.springframework.stereotype.Component;

/**
 * A port of {@code intel/overview.ts}'s {@code getOverview}: what changed, where the
 * money is, what could go wrong. Every figure is an aggregation of the same per-item,
 * per-store intelligence the Sell, Buy, Stores and Products screens show - the overview
 * never has a number of its own.
 *
 * <p>Two cross-track stand-ins feed this engine (see {@link CrossTrackStandIns}): recent
 * decisions (the {@code decisions} module) and the buy-side half of the impact summary
 * (the {@code buy} module's procurement ledger - discovered while porting this class, not
 * one of the two named in the wave-2 brief, but the same real-module-not-a-small-function
 * shape as the other two). Both are named in this class's {@code compute} method at the
 * exact lines they matter.
 */
@Component
class OverviewEngine {

    record Kpi(String key, String label, double value, String format, Double deltaPct, String foot, String tone) {
    }

    record DemandRegion(String label, double pct, String key) {
    }

    record SinceYesterday(int up, int down, int supplierChanges, DemandRegion demandRegion, double opportunityTotal) {
    }

    record Opportunity(String id, String priority, String title, String detail, double impact,
            String impactLabel, String action, String href, String cta) {
    }

    record RiskItem(String id, String level, String label, int count, String unit, String detail, String href) {
    }

    record ChangeEvent(String id, String time, String label, String tone, String href) {
    }

    record Overview(
            SinceYesterday sinceYesterday, List<Kpi> kpis, List<Opportunity> opportunities, List<RiskItem> risks,
            List<ChangeEvent> changes, List<DecisionView> decisions, List<RegionIntel> regions,
            List<StoreIntel> stores, int adoptionPct) {
    }

    private final CatalogSnapshotReader reader;
    private final DealsIndex deals;
    private final RecentDecisionsReader decisionsReader;
    private final ProcurementImpactReader procurementImpact;

    OverviewEngine(CatalogSnapshotReader reader, DealsIndex deals, RecentDecisionsReader decisionsReader,
            ProcurementImpactReader procurementImpact) {
        this.reader = reader;
        this.deals = deals;
        this.decisionsReader = decisionsReader;
        this.procurementImpact = procurementImpact;
    }

    Overview compute() {
        return compute(reader.load());
    }

    record BestSingle(String item, String store, String name, String storeLabel, double monthly, double pct) {
    }

    Overview compute(CatalogSnapshot snapshot) {
        List<RegionIntel> regions = GeoEngine.allRegions(snapshot, deals);
        List<StoreIntel> stores = GeoEngine.allStores(snapshot, deals);

        double pricingOpportunityMonthly = 0;
        double inventoryValue = 0;
        double revW = 0;
        double cogsW = 0;
        Set<String> raise = new LinkedHashSet<>();
        Set<String> lower = new LinkedHashSet<>();
        Set<String> thinMargin = new LinkedHashSet<>();
        Map<String, Integer> lowDemandHits = new LinkedHashMap<>();
        Set<String> overstockStores = new LinkedHashSet<>();
        Map<String, Double> suppliersAboveMarket = new LinkedHashMap<>();
        double procurementAnnual = 0;
        BestSingle bestSingle = null;

        for (StoreRef t : FixtureOrder.stores(snapshot)) {
            double storeCoverSum = 0;
            int storeCount = 0;
            for (ProductRef p : FixtureOrder.sellableProducts(snapshot)) {
                if (!snapshot.priceable(p.itemNumber(), t.storeCode())) {
                    continue;
                }
                PricingModel m = PricingEngine.compute(p.itemNumber(), t.storeCode(), snapshot);
                SellSummary intel = SellEngine.compute(p.itemNumber(), t.storeCode(), snapshot, m);
                revW += intel.currentPrice() * intel.monthlyUnits();
                cogsW += intel.cost() * intel.monthlyUnits();
                inventoryValue += intel.inventoryValue();
                storeCoverSum += intel.weeksOfCover();
                storeCount++;

                if (intel.monthlyOpportunity() > 0) {
                    pricingOpportunityMonthly += intel.monthlyOpportunity();
                    raise.add(p.itemNumber());
                    if (bestSingle == null || intel.monthlyOpportunity() > bestSingle.monthly()) {
                        bestSingle = new BestSingle(p.itemNumber(), t.storeCode(), intel.name(),
                                intel.storeLabel(), intel.monthlyOpportunity(), intel.upliftPct());
                    }
                } else if (intel.upliftPct() < -3) {
                    lower.add(p.itemNumber());
                }
                if (PricingEngine.marginPercent(intel.currentPrice(), intel.cost()) < 26) {
                    thinMargin.add(p.itemNumber());
                }
                if (m.demand() != null && "low".equals(m.demand().level())) {
                    lowDemandHits.merge(p.itemNumber(), 1, Integer::sum);
                }

                // Suppliers seed asynchronously right after a data source connects; treat
                // "not seeded yet" as "nothing to report" rather than crash on an empty panel.
                BuyRecommendation buy = snapshot.suppliers().isEmpty() ? null
                        : BuyEngine.compute(p.itemNumber(), t.storeCode(), snapshot);
                if (buy != null && buy.savingPct() > 4) {
                    suppliersAboveMarket.merge(buy.incumbentSupplierName(), buy.annualSaving(), Double::sum);
                    procurementAnnual += buy.annualSaving();
                }
            }
            if (storeCount > 0 && storeCoverSum / storeCount > 13) {
                overstockStores.add(t.storeCode());
            }
        }

        Set<String> decliningDemand = new LinkedHashSet<>();
        for (var e : lowDemandHits.entrySet()) {
            if (e.getValue() >= 2) {
                decliningDemand.add(e.getKey());
            }
        }
        long marketUp = snapshot.sellableProducts().stream()
                .filter(p -> snapshot.commodity(p.commodity()).pct90AsDouble() > 0.5).count();
        long marketDown = snapshot.sellableProducts().stream()
                .filter(p -> snapshot.commodity(p.commodity()).pct90AsDouble() < -0.5).count();
        List<SupplierRef> supplierSpikes = snapshot.suppliers().stream()
                .filter(s -> Seeded.rand("spike:" + s.id()) > 0.62).toList();
        RegionIntel hottest = regions.stream()
                .sorted((a, b) -> Double.compare(b.demandPct(), a.demandPct())).findFirst().orElseThrow();
        double revenue = stores.stream().mapToDouble(StoreIntel::revenue).sum();
        double grossMarginPct = revW > 0 ? Math.round(((revW - cogsW) / revW) * 1000) / 10.0 : 0;

        // TODO(merge): impact.buy - the buy side of the impact summary - is the buy module's
        // procurement-ledger analytics, stood in as zero deals/zero gain (see
        // CrossTrackStandIns.ProcurementImpactReader). Both KPIs below therefore read low
        // against golden/overview.json ("savings" and "adoption") until that reader is wired
        // in at merge; the sell side (from the seeded deal ledger, DealsIndex) is real.
        DealsIndex.SellImpact sellImpact = deals.sellImpact();
        int totalDeals = sellImpact.deals() + procurementImpact.deals();
        int totalFollowed = sellImpact.followedDeals() + procurementImpact.followedDeals();
        int adoptionPct = (int) Math.round((totalFollowed / (double) Math.max(1, totalDeals)) * 100);
        double procurementSavings = procurementImpact.gained();

        List<Kpi> kpis = new ArrayList<>();
        kpis.add(new Kpi("revenue", "Revenue", revenue, "compact", 6.8, "trailing twelve months", null));
        kpis.add(new Kpi("margin", "Gross margin", grossMarginPct, "pct", 1.4, "across priced lines", "good"));
        kpis.add(new Kpi("savings", "Procurement savings", procurementSavings, "compact",
                null, "realised on purchases at or under target", "good"));
        kpis.add(new Kpi("opportunity", "Pricing opportunity", pricingOpportunityMonthly, "compact",
                null, "per month, at recommended prices", null));
        kpis.add(new Kpi("inventory", "Inventory value", inventoryValue, "compact", null,
                overstockStores.size() + " " + (overstockStores.size() == 1 ? "branch" : "branches") + " overstocked", null));
        kpis.add(new Kpi("adoption", "Recommendation adoption", adoptionPct, "pct", null,
                totalFollowed + " of " + totalDeals + " deals", adoptionPct >= 65 ? "good" : "bad"));

        Map.Entry<String, Double> topSupplier = suppliersAboveMarket.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);

        List<Opportunity> opportunities = new ArrayList<>();
        opportunities.add(new Opportunity("raise", "high", "Raise prices on " + raise.size() + " products",
                bestSingle != null
                        ? "Largest single move: " + bestSingle.name() + " at " + bestSingle.storeLabel() + ", "
                                + Fmt.toFixed(bestSingle.pct(), 0) + "% below the recommendation."
                        : "Every line is at or above its recommendation.",
                Fmt.round2(pricingOpportunityMonthly), "/month", "Review and apply in bulk",
                "/app/sell/bulk?preset=raise", "Review"));
        opportunities.add(new Opportunity("suppliers", "procurement",
                suppliersAboveMarket.size() + " suppliers are priced above market",
                topSupplier != null
                        ? topSupplier.getKey() + " accounts for " + Fmt.money(topSupplier.getValue(), 0) + " of it."
                        : "Every incumbent is at or under target.",
                Fmt.round2(procurementAnnual), "/year", "Renegotiate or move the volume", "/app/buy/bulk", "Review"));
        opportunities.add(new Opportunity("market", "market",
                "Demand up " + Fmt.toFixed(hottest.demandPct(), 0) + "% in the " + hottest.label(),
                hottest.fastestGrowing().name() + " is the fastest-growing line. "
                        + hottest.actions().get(0).count() + " " + (hottest.actions().get(0).count() == 1 ? "product needs" : "products need")
                        + " more stock, " + hottest.actions().get(1).count() + " "
                        + (hottest.actions().get(1).count() == 1 ? "needs" : "need") + " a price change.",
                Fmt.round2(hottest.revenue() * (hottest.demandPct() / 100) * 0.08), "/month",
                "Increase inventory and adjust pricing", "/app/insights?region=" + hottest.key(), "View"));
        if (!overstockStores.isEmpty()) {
            opportunities.add(new Opportunity("overstock", "inventory",
                    overstockStores.size() + " " + (overstockStores.size() == 1 ? "branch is" : "branches are") + " carrying too much stock",
                    "Fast-movement pricing on the heaviest lines frees working capital without breaking the margin floor.",
                    Fmt.round2(inventoryValue * 0.012), "/month", "Price to move",
                    "/app/sell/bulk?store=" + overstockStores.iterator().next() + "&preset=overstock", "Review"));
        }

        List<RiskItem> allRisks = new ArrayList<>();
        allRisks.add(new RiskItem("spike", "red", "Supplier price spike", supplierSpikes.size(), "suppliers",
                supplierSpikes.stream().map(SupplierRef::name).limit(2).reduce((a, b) -> a + ", " + b).orElse(""),
                "/app/buy/bulk"));
        allRisks.add(new RiskItem("demand", "orange", "Demand decline", decliningDemand.size(), "products",
                decliningDemand.stream().limit(2)
                        .map(i -> snapshot.product(i).map(ProductRef::shortName).orElse(i))
                        .reduce((a, b) -> a + ", " + b).orElse(""),
                "/app/products?filter=risk"));
        allRisks.add(new RiskItem("margin", "yellow", "Margin compression", thinMargin.size(), "products",
                "Under 26% gross margin somewhere in the network", "/app/products?filter=risk"));
        allRisks.add(new RiskItem("overstock", "yellow", "Inventory overstock", overstockStores.size(), "branches",
                overstockStores.stream().map(id -> snapshot.store(id).map(GeoEngine::storeCity).orElse(id))
                        .reduce((a, b) -> a + ", " + b).orElse(""),
                "/app/stores"));
        List<RiskItem> risks = allRisks.stream().filter(r -> r.count() > 0).toList();

        long copperLines = snapshot.sellableProducts().stream().filter(p -> "copper".equals(p.commodity())).count();
        SupplierRef spike = supplierSpikes.isEmpty() ? null : supplierSpikes.get(0);
        String thin = thinMargin.isEmpty() ? null : thinMargin.iterator().next();

        List<ChangeEvent> changes = new ArrayList<>();
        changes.add(new ChangeEvent("c1", "10:32 AM",
                "Market price up for " + marketUp + " products, led by " + copperLines + " copper lines", "up",
                "/app/products?sort=trend"));
        if (spike != null) {
            double pct = 3 + Seeded.rand("spike:" + spike.id()) * 3;
            changes.add(new ChangeEvent("c2", "9:45 AM",
                    spike.name() + " raised its quote " + Fmt.toFixed(pct, 1) + "%", "warn", "/app/buy/bulk"));
        }
        changes.add(new ChangeEvent("c3", "9:20 AM",
                "Demand up " + Fmt.toFixed(hottest.demandPct(), 0) + "% in the " + hottest.label(), "up",
                "/app/insights?region=" + hottest.key()));
        if (bestSingle != null) {
            changes.add(new ChangeEvent("c4", "Yesterday",
                    bestSingle.name() + " at " + bestSingle.storeLabel() + " fell " + Fmt.toFixed(bestSingle.pct(), 0)
                            + "% below the recommended price",
                    "info", "/app/sell?item=" + bestSingle.item() + "&store=" + bestSingle.store()));
        }
        if (thin != null) {
            String shortName = snapshot.product(thin).map(ProductRef::shortName).orElse(thin);
            changes.add(new ChangeEvent("c5", "Yesterday", shortName + " crossed the 26% margin threshold",
                    "down", "/app/products?item=" + thin));
        }
        if (marketDown > 0) {
            changes.add(new ChangeEvent("c6", "2 days ago",
                    "Market price softened for " + marketDown + " steel and iron lines", "down",
                    "/app/products?sort=trend"));
        }

        SinceYesterday sinceYesterday = new SinceYesterday((int) marketUp, (int) marketDown, supplierSpikes.size(),
                new DemandRegion(hottest.label(), hottest.demandPct(), hottest.key()),
                Fmt.round2(pricingOpportunityMonthly + procurementAnnual / 12));

        // TODO(merge): "recent decisions" is the decisions module's reader over what a user
        // has actually recorded (intel/decisions.ts's readDecisions()); stood in as an empty
        // list, matching golden/overview.json's own empty-browser-state capture.
        List<DecisionView> decisions = decisionsReader.recent();

        return new Overview(sinceYesterday, kpis, opportunities, risks, changes, decisions, regions, stores, adoptionPct);
    }
}
