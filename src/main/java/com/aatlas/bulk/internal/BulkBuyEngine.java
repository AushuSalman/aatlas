package com.aatlas.bulk.internal;

import com.aatlas.bulk.BuyLine;
import com.aatlas.bulk.BuyLineReader;
import com.aatlas.bulk.SupplierEval;
import com.aatlas.bulk.internal.BulkBuyDtos.AwardView;
import com.aatlas.bulk.internal.BulkBuyDtos.LineView;
import com.aatlas.bulk.internal.BulkBuyDtos.PlanView;
import com.aatlas.bulk.internal.BulkBuyDtos.ProjectionView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Exact port of {@code src/lib/intel/bulk.ts}'s {@code bulkBuyPlan} and
 * {@code projectBuy}: five ways to award a basket into one region.
 */
@Service
public class BulkBuyEngine {

    private final BuyLineReader buyLines;

    public BulkBuyEngine(BuyLineReader buyLines) {
        this.buyLines = buyLines;
    }

    public PlanView plan(String regionKey, List<String> itemNumbers, double qtyMultiplier) {
        List<LineView> lines = new ArrayList<>();
        for (String itemNumber : itemNumbers) {
            BuyLine probe = buyLines.read(itemNumber, regionKey, 1);
            if (!probe.priceable()) {
                continue;
            }
            // A quarter's volume for the region, scaled by what the buyer asked for.
            int qty = (int) Math.max(1, Math.round(
                    (BuyMath.annualVolumeFor(itemNumber, regionKey, probe.incumbent().landed()) / 4) * qtyMultiplier));
            BuyLine intel = buyLines.read(itemNumber, regionKey, qty);
            double currentTotal = BulkPricingEngine.round2(intel.incumbent().landed() * qty);
            lines.add(new LineView(itemNumber, intel.name(), qty, intel.incumbent(), intel.suppliers(),
                    currentTotal));
        }

        ProjectionView lowestCost = project(lines, "lowest-cost", "Lowest cost",
                "Cheapest landed price on every line, whatever the supplier.",
                l -> one(l.suppliers().stream().min(Comparator.comparingDouble(SupplierEval::landed)).orElseThrow()),
                List.of("Maximum saving on paper", "Accepts delivery and quality risk"));

        ProjectionView fastest = project(lines, "fastest", "Fastest",
                "Shortest lead time on every line, among suppliers that deliver on time.",
                l -> {
                    List<SupplierEval> quick = l.suppliers().stream().filter(s -> s.otifPct() >= 85).toList();
                    List<SupplierEval> pool = quick.isEmpty() ? l.suppliers() : quick;
                    SupplierEval sel = pool.stream()
                            .min(Comparator.comparingInt(SupplierEval::leadDays)
                                    .thenComparingDouble(SupplierEval::landed))
                            .orElseThrow();
                    return one(sel);
                },
                List.of("Goods arrive soonest", "Pays for speed"));

        ProjectionView lowestRisk = project(lines, "lowest-risk", "Lowest risk",
                "Only suppliers with 92%+ on-time delivery.",
                l -> {
                    List<SupplierEval> safe = l.suppliers().stream().filter(s -> s.otifPct() >= 92).toList();
                    List<SupplierEval> pool = !safe.isEmpty() ? safe
                            : l.suppliers().stream().filter(s -> s.otifPct() >= 88).toList();
                    List<SupplierEval> finalPool = pool.isEmpty() ? List.of(l.incumbent()) : pool;
                    SupplierEval sel = finalPool.stream().min(Comparator.comparingDouble(SupplierEval::landed))
                            .orElseThrow();
                    return one(sel);
                },
                List.of("Fewest late deliveries", "Some saving left on the table"));

        ProjectionView balanced = project(lines, "balanced", "Balanced",
                "Lowest all-in cost once reliability, lead time and quality are priced in.",
                l -> one(l.suppliers().stream().filter(SupplierEval::recommended).findFirst()
                        .orElse(l.suppliers().get(0))),
                List.of("Best cost after hidden costs", "Reliable enough to plan on"));

        ProjectionView split = project(lines, "split", "Diversified",
                "Volume across the three best all-in suppliers, 40 / 35 / 25.",
                l -> {
                    List<SupplierEval> top = l.suppliers().stream()
                            .sorted(Comparator.comparingDouble(SupplierEval::effective)).limit(3).toList();
                    double[] shares = {0.4, 0.35, 0.25};
                    List<Choice> out = new ArrayList<>();
                    for (int i = 0; i < top.size(); i++) {
                        out.add(new Choice(top.get(i), shares[i]));
                    }
                    return out;
                },
                List.of("Reduced supplier dependency", "Better negotiation leverage", "Lower supply risk"));

        List<ProjectionView> strategies = List.of(lowestCost, fastest, lowestRisk, balanced, split);

        double currentCost = BulkPricingEngine.round2(lines.stream().mapToDouble(LineView::currentTotal).sum());
        double optimizedCost = balanced.totalCost();
        String recommendedKey =
                "High".equals(lowestCost.risk()) || lowestCost.savings() <= balanced.savings() * 1.12
                        ? "balanced" : "lowest-cost";

        double totalUnits = lines.stream().mapToInt(LineView::qty).sum();
        return new PlanView(regionKey, BuyMath.regionLabel(regionKey), lines, totalUnits, currentCost,
                optimizedCost, BulkPricingEngine.round2(currentCost - optimizedCost), strategies, recommendedKey);
    }

    private static List<Choice> one(SupplierEval s) {
        return List.of(new Choice(s, 1));
    }

    private record Choice(SupplierEval supplier, double share) {
    }

    private ProjectionView project(List<LineView> lines, String key, String title, String blurb,
            Function<LineView, List<Choice>> choose, List<String> benefits) {
        double total = 0;
        double otifW = 0;
        double leadW = 0;
        double units = 0;
        List<AwardView> awards = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        String worst = "Low";
        for (LineView l : lines) {
            for (Choice c : choose.apply(l)) {
                double q = l.qty() * c.share();
                total += c.supplier().landed() * q;
                otifW += c.supplier().otifPct() * q;
                leadW += c.supplier().leadDays() * q;
                units += q;
                ids.add(c.supplier().supplierId());
                awards.add(new AwardView(l.itemNumber(), c.supplier().name(), c.supplier().supplierId(),
                        (int) Math.round(c.share() * 100), c.supplier().landed()));
                if ("High".equals(c.supplier().risk())) {
                    worst = "High";
                } else if ("Medium".equals(c.supplier().risk()) && "Low".equals(worst)) {
                    worst = "Medium";
                }
            }
        }
        double current = lines.stream().mapToDouble(LineView::currentTotal).sum();
        String risk = "split".equals(key) ? "Low" : worst;
        double avgOtifPct = units != 0 ? BulkPricingEngine.round1(otifW / units) : 0;
        double avgLeadDays = units != 0 ? Math.round(leadW / units) : 0;
        String dependency = ids.size() >= 3 ? "Low" : ids.size() == 2 ? "Medium" : "High";
        return new ProjectionView(key, title, blurb, BulkPricingEngine.round2(total),
                BulkPricingEngine.round2(current - total),
                current > 0 ? BulkPricingEngine.round1((current - total) / current * 100) : 0, risk, avgOtifPct,
                avgLeadDays, avgOtifPct, ids.size(), dependency, awards, benefits);
    }
}
