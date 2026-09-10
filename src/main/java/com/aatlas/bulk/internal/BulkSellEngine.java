package com.aatlas.bulk.internal;

import com.aatlas.bulk.SellLine;
import com.aatlas.bulk.SellLineReader;
import com.aatlas.bulk.internal.BulkSellDtos.LineView;
import com.aatlas.bulk.internal.BulkSellDtos.PlanView;
import com.aatlas.bulk.internal.BulkSellDtos.ProjectionView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Exact port of {@code src/lib/intel/bulk.ts}'s {@code bulkSellPlan} and
 * {@code projectSell}: three ways to price a basket at one store, and what each does to
 * profit, revenue and turnover.
 */
@Service
public class BulkSellEngine {

    private final SellLineReader sellLines;
    private final OpportunityScoring scoring;
    private final BulkSeedCatalog catalog;

    public BulkSellEngine(SellLineReader sellLines, OpportunityScoring scoring, BulkSeedCatalog catalog) {
        this.sellLines = sellLines;
        this.scoring = scoring;
        this.catalog = catalog;
    }

    public PlanView plan(String storeId, List<String> itemNumbers) {
        List<LineView> lines = new ArrayList<>();
        for (String itemNumber : itemNumbers) {
            SellLine intel = sellLines.read(itemNumber, storeId);
            if (!intel.priceable()) {
                continue;
            }
            OpportunityScoring.Score score = scoring.score(itemNumber, storeId);
            double opportunity = PricingEngine.round2(
                    (intel.recommended() - intel.currentPrice()) * intel.inventoryUnits());
            lines.add(new LineView(itemNumber, intel.name(), intel.cost(), intel.currentPrice(), intel.recommended(),
                    intel.inventoryUnits(), intel.inventoryValue(), intel.weeksOfCover(), opportunity, score.score(),
                    score.tier(), intel));
        }

        ProjectionView current = project(lines, "current", "Current prices", "Leave every price where it is.",
                LineView::current, "Low");
        ProjectionView maxProfit = project(lines, "max-profit", "Max profit",
                "Highest margin the market supports. Volume gives a little.",
                l -> Math.max(l.recommended(), l.intel().stretchPrice()), "Medium");
        ProjectionView fast = project(lines, "fast-movement", "Fast movement",
                "Price to move inventory quickly. Margin gives a little.",
                l -> Math.max(l.intel().marginFloor(), Math.min(l.recommended(), l.current()) * 0.965), "Low");
        ProjectionView balanced = project(lines, "balanced", "Balanced",
                "The recommended price on every line: margin and velocity together.", LineView::recommended, "Low");
        List<ProjectionView> strategies = List.of(maxProfit, fast, balanced);

        double avgCover = lines.isEmpty() ? 0 : lines.stream().mapToDouble(LineView::weeksOfCover).average().orElse(0);
        String recommendedKey;
        if (avgCover > 14 && fast.profit() >= balanced.profit() * 0.94) {
            recommendedKey = "fast-movement";
        } else if (maxProfit.profit() > balanced.profit() * 1.1 && maxProfit.turnoverPct() >= 50) {
            recommendedKey = "max-profit";
        } else {
            recommendedKey = "balanced";
        }

        double totalInventoryValue = PricingEngine.round2(lines.stream().mapToDouble(LineView::inventoryValue).sum());
        double totalOpportunity = PricingEngine.round2(lines.stream().mapToDouble(LineView::opportunity).sum());

        return new PlanView(storeId, StoreLabels.of(catalog, storeId), lines, totalInventoryValue, totalOpportunity,
                current, strategies, recommendedKey);
    }

    private ProjectionView project(List<LineView> lines, String key, String title, String blurb,
            Function<LineView, Double> priceFor, String risk) {
        double unitsSold = 0;
        double revenue = 0;
        double profit = 0;
        double inventory = 0;
        double cogs = 0;
        Map<String, Double> prices = new LinkedHashMap<>();
        for (LineView l : lines) {
            double price = PricingEngine.round2(priceFor.apply(l));
            prices.put(l.itemNumber(), price);
            double baseUnits = Math.min(l.inventoryUnits(), l.intel().monthlyUnits() * 3);
            double ratio = Math.pow(price / l.current(), l.intel().elasticity());
            double units = Math.min(l.inventoryUnits(), Math.round(baseUnits * ratio));
            unitsSold += units;
            inventory += l.inventoryUnits();
            revenue += price * units;
            profit += (price - l.cost()) * units;
            cogs += l.cost() * units;
        }
        double marginPct = revenue > 0 ? PricingEngine.round1(((revenue - cogs) / revenue) * 100) : 0;
        double turnoverPct = inventory > 0 ? PricingEngine.round1((unitsSold / inventory) * 100) : 0;
        return new ProjectionView(key, title, blurb, prices, unitsSold, PricingEngine.round2(revenue),
                PricingEngine.round2(profit), marginPct, turnoverPct, risk);
    }
}
