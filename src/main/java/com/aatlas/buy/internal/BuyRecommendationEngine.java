package com.aatlas.buy.internal;

import com.aatlas.buy.BuyRecommendation;
import com.aatlas.buy.CalcStep;
import com.aatlas.buy.FactorWeight;
import com.aatlas.buy.Lane;
import com.aatlas.buy.SupplierQuote;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The landed-cost panel for one (item, destination, supplier): a port of {@code
 * buildBuyRecommendation} in the frontend's {@code platform/api.ts}. Ex-works quotes per
 * supplier via the price index, landed with freight and duty from {@link LogisticsEngine}
 * (which reads the real logistics reference tables {@code catalog} seeded in wave 1).
 */
@Component
class BuyRecommendationEngine {

    private final CatalogGateway catalog;
    private final SupplierGateway suppliers;
    private final PricingStandIn pricing;

    BuyRecommendationEngine(CatalogGateway catalog, SupplierGateway suppliers, PricingStandIn pricing) {
        this.catalog = catalog;
        this.suppliers = suppliers;
        this.pricing = pricing;
    }

    /**
     * Who a given item sits with today. Deterministic: roughly seven lines in ten sit in the
     * cheaper half of the panel, the rest do not - see the frontend's {@code currentSupplierFor}.
     */
    static SupplierGateway.SupplierRow currentSupplierFor(String itemNumber, List<SupplierGateway.SupplierRow> panel) {
        List<SupplierGateway.SupplierRow> ranked = panel.stream()
                .sorted(Comparator.comparingDouble(SupplierGateway.SupplierRow::priceIndex)).toList();
        int half = (int) Math.ceil(ranked.size() / 2.0);
        List<SupplierGateway.SupplierRow> pool = Seeded.rand(itemNumber, "inc-tier") > 0.3
                ? ranked.subList(0, half)
                : ranked.subList(half, ranked.size());
        return Seeded.pick(itemNumber, "current-sup", pool);
    }

    static String storeName(CatalogGateway.StoreRow store) {
        String base = store.msaName() != null ? store.msaName() : "Branch";
        String city = base.split("-")[0];
        return city + " — " + store.subdivisionCode();
    }

    /** "Dallas" from "Dallas-Fort Worth-Arlington", or the legal name's first word. */
    static String storeCity(CatalogGateway.StoreRow store) {
        String base = store.msaName() != null ? store.msaName()
                : store.legalName() != null ? store.legalName() : store.storeCode();
        return base.split("-")[0].replace(" Branch", "").trim();
    }

    /** "Dallas #100959" - what a branch manager calls it. */
    static String storeLabel(CatalogGateway.StoreRow store) {
        return storeCity(store) + " #" + store.storeCode();
    }

    BuyRecommendation build(String itemNumber, String destinationId, String supplierIdOverride) {
        // The frontend's own buildBuyRecommendation tolerates an unknown item (falls back to
        // the item number as its description, since it is a pure function with no real
        // catalogue to 404 against) - but every other buy endpoint here 404s on one
        // (getBuyIntel and everything built on it), and so does catalog's own
        // GET /products/{item}. A real API is more useful consistent than faithfully
        // replicating a fixture's leniency, so this 404s too.
        CatalogGateway.ProductRow product = catalog.findProduct(itemNumber)
                .orElseThrow(() -> ApiException.notFound("Product", itemNumber));
        CatalogGateway.StoreRow destination = catalog.findStore(destinationId)
                .orElseThrow(() -> ApiException.notFound("Store", destinationId));
        List<SupplierGateway.SupplierRow> panel = suppliers.panel();
        if (panel.isEmpty()) {
            throw ApiException.notFound("Supplier panel", "(empty)");
        }
        CatalogGateway.LogisticsRef logisticsRef = catalog.logistics();

        String description = product.description();
        String homeStore = product.defaultStoreCode() != null
                ? product.defaultStoreCode()
                : catalog.firstStoreCodeInSeedOrder(destination.country());
        PricingStandIn.Model m = pricing.modelFor(itemNumber, homeStore);

        CatalogGateway.LaneRef region = LogisticsEngine.regionForState(logisticsRef, destination.subdivisionCode());
        SupplierGateway.SupplierRow incumbent = currentSupplierFor(itemNumber, panel);
        SupplierGateway.SupplierRow supplier = (supplierIdOverride != null && !supplierIdOverride.isBlank())
                ? panel.stream().filter(s -> s.id().equals(supplierIdOverride)).findFirst().orElse(incumbent)
                : incumbent;
        boolean isOverride = !supplier.id().equals(incumbent.id());
        String key = itemNumber + "|" + supplier.id() + "|" + destinationId;

        double base = m.cost();
        List<SupplierQuote> quotes = new ArrayList<>();
        for (SupplierGateway.SupplierRow s : panel) {
            double exWorks = Js.round2(base * (s.priceIndex() / 100.0)
                    * Seeded.randRange(itemNumber + ":" + s.id(), "q", 0.94, 1.05));
            Lane lane = LogisticsEngine.laneFor(logisticsRef, s.country(), region);
            double freight = Js.round2(exWorks * (lane.freightPct() / 100.0));
            double duty = Js.round2(exWorks * (lane.dutyPct() / 100.0));
            quotes.add(new SupplierQuote(
                    s.id(), s.name(), s.country(), exWorks, freight, duty, Js.round2(exWorks + freight + duty),
                    s.leadTimeDays(), lane.transitDays(), s.leadTimeDays() + lane.transitDays(), s.otifPct(),
                    s.id().equals(supplier.id()), s.id().equals(incumbent.id())));
        }
        quotes = quotes.stream().sorted(Comparator.comparingDouble(SupplierQuote::unitCost)).toList();

        // A primitive double[], not List<Double>: this package may not depend on
        // java.lang.Double (ArchitectureRulesTest.noFloatingPointMoney), which a boxed list
        // would - every .get(i) unboxes.
        double[] prices = quotes.stream().mapToDouble(SupplierQuote::unitCost).toArray();
        double marketLow = prices[0];
        double marketHigh = prices[prices.length - 1];
        double marketMedian = Js.round2(prices[prices.length / 2]);

        SupplierQuote mine = quotes.stream().filter(SupplierQuote::isCurrent).findFirst().orElse(null);
        SupplierQuote theirs = quotes.stream().filter(SupplierQuote::isIncumbent).findFirst().orElse(null);
        Lane lane = LogisticsEngine.laneFor(logisticsRef, supplier.country(), region);
        double currentExWorks = mine != null ? mine.exWorksCost() : base;
        double currentFreight = mine != null ? mine.freightCost() : 0;
        double currentDuty = mine != null ? mine.dutyCost() : 0;
        double currentCost = mine != null ? mine.unitCost() : base;
        double incumbentCost = theirs != null ? theirs.unitCost() : currentCost;

        double rawTarget = Js.round2(marketLow + (marketMedian - marketLow) * 0.35);
        double targetCost = Js.round2(Math.min(currentCost, Math.max(rawTarget, marketLow)));
        double savingPerUnit = Js.round2(currentCost - targetCost);
        int annualUnits = Seeded.randInt(key, "units", 240, 5200);

        String destinationName = storeName(destination);

        PricingStandIn.Model localModel = pricing.modelFor(itemNumber, destinationId);
        PricingStandIn.Model sellModel = localModel.priceable() ? localModel : m;
        double sellPrice = sellModel.currentPrice();
        boolean sellPriceLocal = localModel.priceable();
        double marginNowPct = sellPrice > 0 ? Js.round2(((sellPrice - currentCost) / sellPrice) * 100) : 0;
        double marginAtTargetPct = sellPrice > 0 ? Js.round2(((sellPrice - targetCost) / sellPrice) * 100) : 0;

        List<CalcStep> steps = new ArrayList<>();
        steps.add(new CalcStep(
                isOverride ? "Quote from the supplier being evaluated" : "Quote from current supplier",
                Js.fmtMoney(currentExWorks), supplier.name() + ", ex-works " + supplier.country(), "step"));
        steps.add(new CalcStep("Freight to " + destinationName, "+" + Js.fmtMoney(currentFreight),
                lane.routeNote() + " - " + Js.toFixed(lane.freightPct(), 1) + "% of ex-works value", "step"));
        steps.add(new CalcStep("Duty", "+" + Js.fmtMoney(currentDuty),
                Js.toFixed(lane.dutyPct(), 1) + "% - " + lane.dutyNote(), "step"));
        steps.add(new CalcStep(
                isOverride ? "Landed cost if the line moved" : "Landed cost today", Js.fmtMoney(currentCost),
                isOverride
                        ? "Against " + Js.fmtMoney(incumbentCost) + " with " + incumbent.name() + " today, "
                                + (supplier.leadTimeDays() + lane.transitDays()) + " days from order to dock"
                        : "What this branch actually pays, " + (supplier.leadTimeDays() + lane.transitDays())
                                + " days from order to dock",
                "step"));
        steps.add(new CalcStep("Panel quotes, landed here",
                Js.fmtMoney(marketLow) + " - " + Js.fmtMoney(marketHigh),
                quotes.size() + " suppliers able to serve this item, each on its own lane into the "
                        + region.label(),
                "step"));
        steps.add(new CalcStep("Market median", Js.fmtMoney(marketMedian), "Middle of the panel", "step"));
        steps.add(new CalcStep("Target set at", Js.fmtMoney(targetCost),
                "Best real quote plus 35% of the gap to the median - achievable, not theoretical", "step"));
        steps.add(new CalcStep("Floor", Js.fmtMoney(marketLow),
                "The lowest anyone actually lands here for. The target is never set below it.", "step"));
        steps.add(new CalcStep("Saving per unit", Js.fmtMoney(savingPerUnit),
                Js.localeInt(annualUnits) + " units a year into this branch", "result"));

        double lanePercent = currentCost != 0 ? Js.round2(((currentFreight + currentDuty) / currentCost) * 100) : 0;
        double w1 = Seeded.randRange(key, "w1", 30, 46);
        double w2 = Seeded.randRange(key, "w2", 18, 30);
        double w3 = Seeded.randRange(key, "w3", 12, 24);
        double wSum = w1 + w2 + w3;
        double rest = 100 - lanePercent;
        double scaled1 = Math.round((w1 / wSum) * rest * 10) / 10.0;
        double scaled2 = Math.round((w2 / wSum) * rest * 10) / 10.0;
        List<FactorWeight> weights = List.of(
                new FactorWeight("Panel price spread", scaled1, "down"),
                new FactorWeight("Supplier price index", scaled2, "down"),
                new FactorWeight("Freight and duty into the " + region.label(), lanePercent, "up"),
                new FactorWeight("Volume leverage", Js.round2(100 - lanePercent - scaled1 - scaled2), "down"));

        return new BuyRecommendation(
                itemNumber, description, supplier.id(), supplier.name(), m.priceable(),
                destinationId, destinationName, region.label(), lane,
                incumbent.id(), incumbent.name(), incumbentCost, isOverride,
                sellPrice, sellPriceLocal, marginNowPct, marginAtTargetPct, Js.round2(marginAtTargetPct - marginNowPct),
                Js.round2(sellPrice - currentCost), Js.round2(sellPrice - targetCost),
                currentExWorks, currentFreight, currentDuty, currentCost, targetCost, savingPerUnit,
                currentCost != 0 ? Js.round2((savingPerUnit / currentCost) * 100) : 0,
                annualUnits, Js.round2(savingPerUnit * annualUnits),
                quotes, marketLow, marketMedian, marketHigh,
                steps, weights, marketLow);
    }
}
