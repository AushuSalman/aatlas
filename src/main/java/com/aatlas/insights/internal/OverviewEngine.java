package com.aatlas.insights.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.decisions.Decision;
import com.aatlas.decisions.DecisionRecorder;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesHistory.GroupStats;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Spec 3.7: what changed, where the money is, what could go wrong - every figure an
 * aggregation of the tenant's own rows through {@link InsightsData}, or labelled reference
 * data. Every KPI that has no input (no sales, no costed rows, no purchases, no priceable
 * pairs, no inventory, no decisions) reports a null value and its own {@code locked} reason
 * rather than a zero.
 */
@Component
class OverviewEngine {

    record Kpi(String key, String label, Double value, String format, Double deltaPct, String foot, String tone,
            String locked) {
    }

    record DemandRegion(String label, double pct, String key) {
    }

    record SinceYesterday(int up, int down, int supplierChanges, DemandRegion demandRegion, double opportunityTotal) {
    }

    record Opportunity(String id, String priority, String title, String detail, double impact,
            String impactLabel, String action, String href, String cta, String source) {
    }

    record RiskItem(String id, String level, String label, int count, String unit, String detail, String href) {
    }

    record ChangeEvent(String id, String time, String label, String tone, String href) {
    }

    record Overview(
            SinceYesterday sinceYesterday, List<Kpi> kpis, List<Opportunity> opportunities, List<RiskItem> risks,
            List<ChangeEvent> changes, List<Decision> decisions, List<GeoEngine.RegionIntel> regions,
            List<GeoEngine.StoreIntel> stores, Integer adoptionPct) {
    }

    private final InsightsDataLoader loader;
    private final GeoEngine geoEngine;
    private final BuyEngine buyEngine;
    private final PurchaseHistory purchaseHistory;
    private final DecisionRecorder decisionRecorder;
    private final JdbcTemplate jdbc;

    OverviewEngine(InsightsDataLoader loader, GeoEngine geoEngine, BuyEngine buyEngine,
            PurchaseHistory purchaseHistory, DecisionRecorder decisionRecorder, JdbcTemplate jdbc) {
        this.loader = loader;
        this.geoEngine = geoEngine;
        this.buyEngine = buyEngine;
        this.purchaseHistory = purchaseHistory;
        this.decisionRecorder = decisionRecorder;
        this.jdbc = jdbc;
    }

    Overview compute() {
        InsightsData data = loader.load();
        List<GeoEngine.RegionIntel> regions = geoEngine.allRegions(data);
        List<GeoEngine.StoreIntel> stores = geoEngine.allStores(data);

        // -- opportunity, inventory, thin margin, low demand - one pass over every priceable pair
        BigDecimal opportunityMonthly = BigDecimal.ZERO;
        BigDecimal inventoryValue = BigDecimal.ZERO;
        int overLinesCount = 0;
        Set<String> thinMargin = new LinkedHashSet<>();
        Map<String, Integer> lowDemandHits = new LinkedHashMap<>();
        Map<String, BigDecimal> storeCoverSum = new LinkedHashMap<>();
        Map<String, Integer> storeCoverCount = new LinkedHashMap<>();
        int up = 0;
        int down = 0;
        String bestItem = null;
        String bestStoreLabel = null;
        double bestMonthly = 0;
        double bestPct = 0;

        for (PairFacts f : data.pairsByKey().values()) {
            if (f.hasInventory() && f.cost() != null) {
                BigDecimal iv = f.inventoryValue();
                if (iv != null) {
                    inventoryValue = inventoryValue.add(iv);
                }
                BigDecimal cover = f.weeksOfCover();
                if (cover != null) {
                    storeCoverSum.merge(f.storeKey(), cover, BigDecimal::add);
                    storeCoverCount.merge(f.storeKey(), 1, Integer::sum);
                }
            }
            BigDecimal avg30 = f.pair().avgPrice30();
            BigDecimal avg90p = f.pair().avgPrice90p();
            if (avg30 != null && avg90p != null && avg90p.signum() > 0) {
                double move = avg30.divide(avg90p, 6, RoundingMode.HALF_UP).doubleValue() - 1;
                if (move >= 0.02) {
                    up++;
                } else if (move <= -0.02) {
                    down++;
                }
            }
            if (!f.priceable()) {
                continue;
            }
            BigDecimal monthlyOpp = f.monthlyOpportunity();
            if (monthlyOpp != null && monthlyOpp.signum() > 0) {
                opportunityMonthly = opportunityMonthly.add(monthlyOpp);
                overLinesCount++;
                if (monthlyOpp.doubleValue() > bestMonthly) {
                    bestMonthly = monthlyOpp.doubleValue();
                    bestItem = f.pair().itemNumber();
                    bestStoreLabel = f.pair().storeLabel();
                    bestPct = Fmt.dv0(f.upliftPct());
                }
            }
            BigDecimal margin = f.marginPct();
            if (margin != null && margin.doubleValue() < 22) {
                thinMargin.add(f.pair().itemNumber());
            }
            if (f.demand() != null && "low".equals(f.demand().level())) {
                lowDemandHits.merge(f.pair().itemNumber(), 1, Integer::sum);
            }
        }
        Set<String> overstockStores = new LinkedHashSet<>();
        for (var e : storeCoverSum.entrySet()) {
            int count = storeCoverCount.getOrDefault(e.getKey(), 0);
            if (count > 0 && e.getValue().doubleValue() / count > 13) {
                overstockStores.add(e.getKey());
            }
        }
        Set<String> decliningDemand = new LinkedHashSet<>();
        for (var e : lowDemandHits.entrySet()) {
            if (e.getValue() >= 2) {
                decliningDemand.add(e.getKey());
            }
        }

        // -- procurement, one BuyEngine call per item (purchases are item-level, not per-store)
        BigDecimal procurementAnnual = BigDecimal.ZERO;
        Map<String, BigDecimal> supplierSavingAnnual = new LinkedHashMap<>();
        for (var entry : data.pairsByItem().entrySet()) {
            List<PairFacts> pairs = entry.getValue();
            if (pairs.isEmpty()) {
                continue;
            }
            buyEngine.compute(pairs.get(0), data.today()).ifPresent(buy -> {
                if (buy.annualSaving() != null && buy.annualSaving().signum() > 0) {
                    if (buy.incumbentSupplierName() != null) {
                        supplierSavingAnnual.merge(buy.incumbentSupplierName(), buy.annualSaving(), BigDecimal::add);
                    }
                }
            });
        }
        for (BigDecimal v : supplierSavingAnnual.values()) {
            procurementAnnual = procurementAnnual.add(v);
        }

        // -- supplier price spikes: W30 avg landed vs W90-prior avg landed, >3% up
        int supplierSpikeCount = 0;
        List<String> spikeSupplierNames = new ArrayList<>();
        Window w30 = Window.trailingDays(data.today(), 30);
        Window w90prior = Window.trailingDays(data.today(), 90).prior();
        Map<String, PurchaseHistory.SupplierPurchases> recent = index(purchaseHistory.bySupplier(w30));
        Map<String, PurchaseHistory.SupplierPurchases> earlier = index(purchaseHistory.bySupplier(w90prior));
        for (var e : recent.entrySet()) {
            PurchaseHistory.PoStats now = e.getValue().w12();
            PurchaseHistory.SupplierPurchases prior = earlier.get(e.getKey());
            if (now == null || now.avgLanded() == null || prior == null || prior.w12() == null
                    || prior.w12().avgLanded() == null || prior.w12().avgLanded().signum() == 0) {
                continue;
            }
            if (now.avgLanded().doubleValue() > prior.w12().avgLanded().doubleValue() * 1.03) {
                supplierSpikeCount++;
                spikeSupplierNames.add(e.getKey());
            }
        }

        // -- hottest region by growth ("demand region")
        GeoEngine.RegionIntel hottest = regions.stream()
                .max(java.util.Comparator.comparingDouble(GeoEngine.RegionIntel::demandPct))
                .orElse(null);

        SalesStats tenantW12 = data.tenantW12();
        SalesStats tenantW12Prior = data.tenantW12Prior();
        double revenue = tenantW12.revenue() == null ? 0 : tenantW12.revenue().doubleValue();
        Double revenueDeltaPct = Fmt.dv(PricingMath.pct(
                orZero(tenantW12.revenue()).subtract(orZero(tenantW12Prior.revenue())), orZero(tenantW12Prior.revenue())));

        SalesHistory.Coverage coverage = data.bulk().coverage();
        String revenueFoot = coverage.months() >= 12 ? "trailing twelve months" : coverage.months() + " months of history";
        if (coverage.noBranchRevenuePct() != null && coverage.noBranchRevenuePct().signum() > 0) {
            revenueFoot += " + incl. " + Fmt.toFixed(coverage.noBranchRevenuePct().doubleValue(), 0) + "% with no branch";
        }

        Double marginValue = Fmt.dv(tenantW12.grossMarginPct());
        Double marginDeltaPct = marginValue == null || tenantW12Prior.grossMarginPct() == null ? null
                : Fmt.round2(marginValue - tenantW12Prior.grossMarginPct().doubleValue());
        String marginFoot = "on " + Fmt.toFixed(tenantW12.costCoveragePct().doubleValue(), 0) + "% of units with a cost";

        BigDecimal saved = data.savedTotalW12();
        BigDecimal savedPrior = data.savedTotalW12Prior();
        BigDecimal overpaid = data.overpaidTotalW12();
        boolean hasPurchases = purchaseHistory.hasHistory();
        Double savingsDeltaPct = Fmt.dv(PricingMath.pct(saved.subtract(savedPrior), savedPrior));
        String savingsFoot = "gross, vs your trailing-year average landed cost; " + Fmt.money(Fmt.dv0(overpaid))
                + " overpaid (net " + Fmt.money(saved.subtract(overpaid).doubleValue()) + ")";

        boolean anyPriceable = data.pairsByKey().values().stream().anyMatch(PairFacts::priceable);
        String opportunityFoot = overLinesCount + " " + (overLinesCount == 1 ? "line" : "lines") + " priced below recommendation";

        boolean staleInventory = data.bulk().hasInventory() && data.bulk().inventoryAsOf() != null
                && data.bulk().inventoryAsOf().isBefore(data.today().minusDays(90));
        String inventoryFoot = data.bulk().hasInventory()
                ? "as of " + data.bulk().inventoryAsOf() + "; " + overstockStores.size() + " "
                        + (overstockStores.size() == 1 ? "branch" : "branches") + " over 13 weeks of cover"
                : "no stock count on file";

        Double adoptionValue = data.networkAdoption().followRatePct().orElse(null);
        Double adoptionPrior = data.networkAdoptionPrior().followRatePct().orElse(null);
        Double adoptionDeltaPct = adoptionValue == null || adoptionPrior == null ? null
                : Fmt.round2(adoptionValue - adoptionPrior);
        String adoptionFoot = data.networkAdoption().total() + " decisions";

        List<Kpi> kpis = new ArrayList<>();
        kpis.add(new Kpi("revenue", "Revenue", tenantW12.any() ? revenue : null, "compact",
                tenantW12.any() ? revenueDeltaPct : null, revenueFoot, null, tenantW12.any() ? null : "no sales"));
        kpis.add(new Kpi("margin", "Gross margin", marginValue, "pct", marginDeltaPct, marginFoot,
                marginValue != null && marginValue >= 27 ? "good" : null, marginValue == null ? "no costed rows" : null));
        kpis.add(new Kpi("savings", "Procurement savings", hasPurchases ? saved.doubleValue() : null, "compact",
                hasPurchases ? savingsDeltaPct : null, savingsFoot, "good", hasPurchases ? null : "no purchases"));
        kpis.add(new Kpi("opportunity", "Pricing opportunity", anyPriceable ? opportunityMonthly.doubleValue() : null,
                "compact", null, opportunityFoot, null, anyPriceable ? null : "no priceable pairs"));
        kpis.add(new Kpi("inventory", "Inventory value", data.bulk().hasInventory() ? inventoryValue.doubleValue() : null,
                "compact", null, inventoryFoot, null,
                !data.bulk().hasInventory() ? "no inventory" : staleInventory ? "stock count is stale" : null));
        kpis.add(new Kpi("adoption", "Recommendation adoption", adoptionValue, "pct", adoptionDeltaPct, adoptionFoot,
                adoptionValue != null && adoptionValue >= 65 ? "good" : adoptionValue != null ? "bad" : null,
                adoptionValue == null ? "no decisions" : null));

        Map.Entry<String, BigDecimal> topSupplier = supplierSavingAnnual.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);

        List<Opportunity> opportunities = new ArrayList<>();
        opportunities.add(new Opportunity("raise", "high", "Raise prices on " + overLinesCount + " lines",
                bestItem != null
                        ? "Largest single move: " + bestItem + " at " + bestStoreLabel + ", "
                                + Fmt.toFixed(bestPct, 0) + "% below the recommendation."
                        : "Every line is at or above its recommendation.",
                Fmt.round2(opportunityMonthly.doubleValue()), "/month", "Review and apply in bulk",
                "/app/sell/bulk?preset=raise", "Review", "rows"));
        opportunities.add(new Opportunity("suppliers", "procurement",
                supplierSavingAnnual.size() + " suppliers are priced above target",
                topSupplier != null
                        ? topSupplier.getKey() + " accounts for " + Fmt.money(topSupplier.getValue().doubleValue(), 0) + " of it."
                        : "Every incumbent is at or under target.",
                Fmt.round2(procurementAnnual.doubleValue()), "/year", "Renegotiate or move the volume", "/app/buy/bulk",
                "Review", "rows"));
        if (hottest != null && hottest.demandPct() > 0) {
            opportunities.add(new Opportunity("market", "market",
                    "Demand up " + Fmt.toFixed(hottest.demandPct(), 0) + "% in the " + hottest.label(),
                    hottest.fastestGrowing().name() + " is the fastest-growing line.",
                    Fmt.round2(hottest.revenue() * (hottest.demandPct() / 100) * 0.08), "/month",
                    "Increase inventory and adjust pricing", "/app/insights?region=" + hottest.key(), "View",
                    "model-estimate"));
        }
        if (!overstockStores.isEmpty()) {
            opportunities.add(new Opportunity("overstock", "inventory",
                    overstockStores.size() + " " + (overstockStores.size() == 1 ? "branch is" : "branches are")
                            + " carrying too much stock",
                    "Fast-movement pricing on the heaviest lines frees working capital without breaking the margin floor.",
                    Fmt.round2(inventoryValue.doubleValue() * 0.012), "/month", "Price to move",
                    "/app/sell/bulk?store=" + overstockStores.iterator().next() + "&preset=overstock", "Review",
                    "model-estimate"));
        }

        List<RiskItem> allRisks = new ArrayList<>();
        allRisks.add(new RiskItem("spike", "red", "Supplier price spike", supplierSpikeCount, "suppliers",
                String.join(", ", spikeSupplierNames.stream().limit(2).toList()), "/app/buy/bulk"));
        allRisks.add(new RiskItem("demand", "orange", "Demand decline", decliningDemand.size(), "products",
                String.join(", ", decliningDemand.stream().limit(2).toList()), "/app/products?filter=risk"));
        allRisks.add(new RiskItem("margin", "yellow", "Margin compression", thinMargin.size(), "products",
                "Under 22% gross margin somewhere in the network", "/app/products?filter=risk"));
        allRisks.add(new RiskItem("overstock", "yellow", "Inventory overstock", overstockStores.size(), "branches",
                String.join(", ", overstockStores), "/app/stores"));
        List<RiskItem> risks = allRisks.stream().filter(r -> r.count() > 0).toList();

        List<ChangeEvent> changes = realChanges(data.today());

        SinceYesterday sinceYesterday = new SinceYesterday(up, down, supplierSpikeCount,
                hottest == null ? new DemandRegion("", 0, "") : new DemandRegion(hottest.label(), hottest.demandPct(), hottest.key()),
                Fmt.round2(opportunityMonthly.doubleValue() + procurementAnnual.doubleValue() / 12));

        List<Decision> decisions = decisionRecorder.list(5, null).items();

        return new Overview(sinceYesterday, kpis, opportunities, risks, changes, decisions, regions, stores,
                adoptionValue == null ? null : (int) Math.round(adoptionValue));
    }

    /** Real events: import commits, price applies, supplier awards, decisions - last 6, newest first. */
    private List<ChangeEvent> realChanges(LocalDate today) {
        java.util.UUID tenantId = TenantContext.requireTenantId();
        record Raw(java.time.Instant at, String label, String tone, String href) {
        }
        List<Raw> raw = new ArrayList<>();

        jdbc.query("""
                select kind, loaded_rows, committed_at from import_batches
                 where tenant_id = ? and status = 'COMMITTED' and committed_at is not null
                 order by committed_at desc limit 6
                """, (rs, i) -> new Raw(rs.getTimestamp("committed_at").toInstant(),
                "Imported " + rs.getInt("loaded_rows") + " " + rs.getString("kind") + " rows", "up", "/app/data"),
                tenantId).forEach(raw::add);

        jdbc.query("""
                select source, count(*) n, max(created_at) at from product_prices
                 where tenant_id = ? and source in ('applied', 'wizard')
                 group by source, date_trunc('minute', created_at) order by at desc limit 6
                """, (rs, i) -> new Raw(rs.getTimestamp("at").toInstant(),
                rs.getInt("n") + " price" + (rs.getInt("n") == 1 ? "" : "s") + " " + rs.getString("source"),
                "info", "/app/sell/bulk"), tenantId).forEach(raw::add);

        jdbc.query("""
                select item_number, supplier_name, created_at from purchase_order
                 where tenant_id = ? and source = 'award' order by created_at desc limit 6
                """, (rs, i) -> new Raw(rs.getTimestamp("created_at").toInstant(),
                "Awarded " + rs.getString("item_number") + " to " + rs.getString("supplier_name"), "up", "/app/buy"),
                tenantId).forEach(raw::add);

        for (Decision d : decisionRecorder.list(6, null).items()) {
            raw.add(new Raw(d.at(), d.title(), "info", "/app/sell?item=" + d.itemNumber()));
        }

        return raw.stream()
                .sorted((a, b) -> b.at().compareTo(a.at()))
                .limit(6)
                .map(r -> new ChangeEvent(String.valueOf(r.at().toEpochMilli()), r.at().toString(), r.label(), r.tone(), r.href()))
                .toList();
    }

    private static Map<String, PurchaseHistory.SupplierPurchases> index(List<PurchaseHistory.SupplierPurchases> list) {
        Map<String, PurchaseHistory.SupplierPurchases> out = new LinkedHashMap<>();
        for (PurchaseHistory.SupplierPurchases sp : list) {
            out.put(sp.supplierKey(), sp);
        }
        return out;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
