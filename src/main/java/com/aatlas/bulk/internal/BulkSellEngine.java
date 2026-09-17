package com.aatlas.bulk.internal;

import com.aatlas.bulk.SellLine;
import com.aatlas.bulk.SellLineReader;
import com.aatlas.bulk.internal.BulkSellDtos.LineView;
import com.aatlas.bulk.internal.BulkSellDtos.PlanView;
import com.aatlas.bulk.internal.BulkSellDtos.ProjectionView;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.BulkModelReader;
import com.aatlas.history.BulkModelReader.BulkModel;
import com.aatlas.history.BulkModelReader.PairModel;
import com.aatlas.history.Catalogue;
import com.aatlas.history.Catalogue.StoreRef;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * {@code bulkSellPlan} and {@code projectSell}: three ways to price a basket at one store,
 * and what each does to profit, revenue and turnover - over {@code sell.SellLines} for
 * every per-line figure and {@code history.BulkModelReader} for the stock count's as-of
 * date, which the sell seam does not carry.
 */
@Service
public class BulkSellEngine {

    private final SellLineReader sellLines;
    private final OpportunityScoring scoring;
    private final BulkModelReader bulkModelReader;
    private final Catalogue catalogue;
    private final AatlasClock clock;

    public BulkSellEngine(SellLineReader sellLines, OpportunityScoring scoring, BulkModelReader bulkModelReader,
            Catalogue catalogue, AatlasClock clock) {
        this.sellLines = sellLines;
        this.scoring = scoring;
        this.bulkModelReader = bulkModelReader;
        this.catalogue = catalogue;
        this.clock = clock;
    }

    public PlanView plan(String storeId, List<String> itemNumbers) {
        Map<String, LocalDate> inventoryAsOf = inventoryAsOfByItem(storeId);

        List<LineView> lines = new ArrayList<>();
        for (String itemNumber : itemNumbers) {
            SellLine intel = sellLines.read(itemNumber, storeId);
            if (!intel.priceable()) {
                continue;
            }
            OpportunityScoring.Score score = scoring.score(intel);
            boolean inventoryLocked = intel.locked().contains("inventory");
            Double opportunity = inventoryLocked ? null
                    : round2((intel.recommended() - intel.currentPrice()) * intel.inventoryUnits());
            lines.add(new LineView(itemNumber, intel.name(), intel.cost(), intel.currentPrice(), intel.recommended(),
                    intel.inventoryUnits(), intel.inventoryValue(), intel.weeksOfCover(), opportunity, score.score(),
                    score.tier(), intel, intel.sources(), intel.locked(), inventoryAsOf.get(itemNumber)));
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
        double maxProfitTurnover = maxProfit.turnoverPct() == null ? 0 : maxProfit.turnoverPct();
        String recommendedKey;
        if (avgCover > 14 && fast.profit() >= balanced.profit() * 0.94) {
            recommendedKey = "fast-movement";
        } else if (maxProfit.profit() > balanced.profit() * 1.1 && maxProfitTurnover >= 50) {
            recommendedKey = "max-profit";
        } else {
            recommendedKey = "balanced";
        }

        double totalInventoryValue = round2Prim(lines.stream().mapToDouble(LineView::inventoryValue).sum());
        double totalOpportunity = round2Prim(lines.stream()
                .filter(l -> l.opportunity() != null)
                .mapToDouble(LineView::opportunity)
                .sum());

        return new PlanView(storeId, storeLabel(storeId), lines, totalInventoryValue, totalOpportunity,
                current, strategies, recommendedKey);
    }

    /** The requested items' stock-count as-of dates, from the tenant's real bulk model - null when unknown. */
    private Map<String, LocalDate> inventoryAsOfByItem(String storeId) {
        Map<String, LocalDate> result = new HashMap<>();
        catalogue.store(storeId).map(StoreRef::id).ifPresent(storeUuid -> {
            BulkModel model = bulkModelReader.bulkModel(storeUuid, clock.today());
            for (PairModel pair : model.pairs()) {
                if (pair.onHand() != null) {
                    result.put(pair.itemNumber(), pair.onHand().asOf());
                }
            }
        });
        return result;
    }

    private String storeLabel(String storeId) {
        return StoreLabels.of(catalogue, storeId);
    }

    private ProjectionView project(List<LineView> lines, String key, String title, String blurb,
            Function<LineView, Double> priceFor, String risk) {
        double unitsSold = 0;
        double revenue = 0;
        double profit = 0;
        double inventory = 0;
        boolean anyInventoryKnown = false;
        double cogs = 0;
        Map<String, Double> prices = new LinkedHashMap<>();
        for (LineView l : lines) {
            double price = round2Prim(priceFor.apply(l));
            prices.put(l.itemNumber(), price);
            boolean inventoryKnown = !l.intel().locked().contains("inventory");
            double baseUnits = inventoryKnown
                    ? Math.min(l.inventoryUnits(), l.intel().monthlyUnits() * 3)
                    : l.intel().monthlyUnits() * 3;
            double ratio = Math.pow(price / l.current(), l.intel().elasticity());
            double units = inventoryKnown
                    ? Math.min(l.inventoryUnits(), Math.round(baseUnits * ratio))
                    : Math.round(baseUnits * ratio);
            unitsSold += units;
            if (inventoryKnown) {
                inventory += l.inventoryUnits();
                anyInventoryKnown = true;
            }
            revenue += price * units;
            profit += (price - l.cost()) * units;
            cogs += l.cost() * units;
        }
        double marginPct = revenue > 0 ? round1Prim(((revenue - cogs) / revenue) * 100) : 0;
        Double turnoverPct = anyInventoryKnown && inventory > 0 ? round1(unitsSold / inventory * 100) : null;
        return new ProjectionView(key, title, blurb, prices, unitsSold, round2Prim(revenue),
                round2Prim(profit), marginPct, turnoverPct, risk);
    }

    private static double round2Prim(double n) {
        return Math.round(n * 100) / 100.0;
    }

    private static double round1Prim(double n) {
        return Math.round(n * 10) / 10.0;
    }

    private static Double round2(double n) {
        return round2Prim(n);
    }

    private static Double round1(double n) {
        return round1Prim(n);
    }
}
