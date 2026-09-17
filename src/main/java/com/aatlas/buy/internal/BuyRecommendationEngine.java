package com.aatlas.buy.internal;

import com.aatlas.buy.BuyRecommendation;
import com.aatlas.buy.CalcStep;
import com.aatlas.buy.FactorWeight;
import com.aatlas.buy.Lane;
import com.aatlas.buy.SupplierQuote;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Catalogue;
import com.aatlas.history.PriceLadder;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The landed-cost panel for one (item, destination, supplier): spec-A S2 "buy" / S3.5. Every
 * figure is either read through {@code history} (incumbent, quotes, cost, sell price) or
 * derived from those real numbers; a step with no input is skipped, never zeroed, and
 * {@link BuyRecommendation#incumbentSupplierId()} is null with a reason when there is
 * genuinely nobody to compare against.
 */
@Component
class BuyRecommendationEngine {

    private final Catalogue catalogue;
    private final CatalogGateway catalog;
    private final SupplierGateway supplierGateway;
    private final PurchaseHistory purchases;
    private final SalesHistory sales;
    private final PriceLadder ladder;
    private final AatlasClock clock;

    BuyRecommendationEngine(Catalogue catalogue, CatalogGateway catalog, SupplierGateway supplierGateway,
            PurchaseHistory purchases, SalesHistory sales, PriceLadder ladder, AatlasClock clock) {
        this.catalogue = catalogue;
        this.catalog = catalog;
        this.supplierGateway = supplierGateway;
        this.purchases = purchases;
        this.sales = sales;
        this.ladder = ladder;
        this.clock = clock;
    }

    static String storeCity(Catalogue.StoreRef store) {
        String base = store.msaName() != null && !store.msaName().isBlank() ? store.msaName() : store.legalName();
        return base.split("-")[0].replace(" Branch", "").trim();
    }

    static String storeLabel(Catalogue.StoreRef store) {
        return storeCity(store) + " #" + store.storeCode();
    }

    /** One supplier's quote, with the landed figure the market stats and the DTO share. */
    private record QuoteCalc(SupplierQuote quote, String supplierKey) {
    }

    BuyRecommendation build(String itemNumber, String destinationId, String supplierIdOverride) {
        Catalogue.ProductRef product = catalogue.product(itemNumber)
                .orElseThrow(() -> ApiException.notFound("Product", itemNumber));
        Catalogue.StoreRef destination = catalogue.store(destinationId)
                .orElseThrow(() -> ApiException.notFound("Store", destinationId));
        LocalDate today = clock.today();

        List<SupplierGateway.Quote> links = supplierGateway.quotesFor(product.id());
        boolean noLinksAtAll = links.isEmpty();
        List<SupplierGateway.SupplierRow> quotablePanel = noLinksAtAll
                ? supplierGateway.panel()
                : List.of();

        Optional<PurchaseHistory.SupplierShare> incumbentShare = purchases.incumbent(itemNumber, today);
        String incumbentId = null;
        String incumbentName = null;
        String incumbentCountry = null;
        String incumbentReason = null;
        BigDecimal incumbentCost = null;
        String incumbentCostSource = null;
        if (incumbentShare.isPresent()) {
            PurchaseHistory.SupplierShare share = incumbentShare.get();
            incumbentId = share.supplierKey();
            incumbentName = share.name();
            incumbentCountry = share.country();
            PurchaseHistory.PoStats w90 = purchases.itemSupplier(itemNumber, incumbentId, Window.trailingDays(today, 90));
            if (w90.any()) {
                incumbentCost = w90.avgLanded();
                incumbentCostSource = "observed-90d";
            } else {
                PurchaseHistory.PoStats w12 = purchases.itemSupplier(itemNumber, incumbentId, Window.trailingMonths(today, 12));
                if (w12.any()) {
                    incumbentCost = w12.avgLanded();
                    incumbentCostSource = "observed-12m";
                }
            }
        } else if (!links.isEmpty()) {
            SupplierGateway.Quote cheapest = links.stream()
                    .filter(l -> l.exWorks() != null)
                    .min((a, b) -> a.exWorks().compareTo(b.exWorks()))
                    .orElse(links.get(0));
            incumbentId = cheapest.supplier().id();
            incumbentName = cheapest.supplier().name();
            incumbentCountry = cheapest.supplier().country();
            incumbentCostSource = "supplier-list";
        } else {
            incumbentReason = "No purchase history for this item — pick a supplier to compare.";
        }

        String chosenSupplierId = supplierIdOverride != null && !supplierIdOverride.isBlank()
                ? supplierIdOverride
                : incumbentId;

        CatalogGateway.LogisticsRef logisticsRef = catalog.logistics();
        CatalogGateway.LaneRef region = LogisticsEngine.regionForState(logisticsRef, destination.subdivisionCode());

        List<QuoteCalc> calcs = new ArrayList<>();
        if (noLinksAtAll) {
            for (SupplierGateway.SupplierRow s : quotablePanel) {
                calcs.add(new QuoteCalc(new SupplierQuote(s.id(), s.name(), s.country(), null, null, null, null,
                        s.leadTimeDays(), 0, s.leadTimeDays(), s.otifPct(), s.id().equals(chosenSupplierId),
                        s.id().equals(incumbentId), null, null, null), s.id()));
            }
        } else {
            for (SupplierGateway.Quote link : links) {
                calcs.add(quoteFor(itemNumber, link, logisticsRef, region, today, chosenSupplierId, incumbentId));
            }
        }

        List<QuoteCalc> quoted = calcs.stream().filter(c -> c.quote().unitCost() != null).toList();
        BigDecimal marketLow = quoted.stream().map(c -> c.quote().unitCost()).min(BigDecimal::compareTo).orElse(null);
        BigDecimal marketHigh = quoted.stream().map(c -> c.quote().unitCost()).max(BigDecimal::compareTo).orElse(null);
        BigDecimal marketMedian = median(quoted.stream().map(c -> c.quote().unitCost()).sorted().toList());

        List<SupplierQuote> quotes = calcs.stream()
                .map(QuoteCalc::quote)
                .sorted((a, b) -> {
                    if (a.unitCost() == null && b.unitCost() == null) {
                        return 0;
                    }
                    if (a.unitCost() == null) {
                        return 1;
                    }
                    if (b.unitCost() == null) {
                        return -1;
                    }
                    return a.unitCost().compareTo(b.unitCost());
                })
                .toList();

        SupplierQuote mine = quotes.stream().filter(SupplierQuote::isCurrent).findFirst().orElse(null);
        String supplierId = chosenSupplierId != null ? chosenSupplierId : (mine != null ? mine.supplierId() : null);
        String supplierName = mine != null ? mine.name()
                : (supplierId != null && supplierId.equals(incumbentId) ? incumbentName : supplierId);
        boolean isOverride = incumbentId != null && supplierId != null && !supplierId.equals(incumbentId);

        Optional<Resolved> costResolved = ladder.cost(product.id(), destination.id(), today);
        BigDecimal currentCost = costResolved.map(Resolved::value).orElse(null);
        String currentCostSource = costResolved.map(Resolved::source).orElse(null);

        Optional<Resolved> priceResolved = ladder.currentPrice(product.id(), destination.id(), today);
        BigDecimal sellPrice = priceResolved.map(Resolved::value).orElse(null);
        String sellPriceSource = priceResolved.map(Resolved::source).orElse(null);
        boolean sellPriceLocal = priceResolved.isPresent() && !Resolved.SALES_ITEM_12M.equals(priceResolved.get().source());
        boolean priceable = sellPrice != null;

        int n = quoted.size();
        BigDecimal targetCost;
        if (n >= 2) {
            BigDecimal raw = marketLow.add(marketMedian.subtract(marketLow).multiply(new BigDecimal("0.35")));
            targetCost = currentCost != null ? PricingMath.min(currentCost, PricingMath.max(marketLow, raw)) : raw;
        } else if (n == 1) {
            BigDecimal landed = quoted.get(0).quote().unitCost();
            targetCost = currentCost != null ? PricingMath.min(currentCost, landed) : landed;
        } else {
            targetCost = null;
        }
        if (targetCost != null) {
            targetCost = targetCost.setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal savingPerUnit = currentCost != null && targetCost != null
                ? currentCost.subtract(targetCost).setScale(4, RoundingMode.HALF_UP) : null;
        BigDecimal savingPct = PricingMath.pct(savingPerUnit, currentCost);

        AnnualUnits annual = AnnualUnits.forStore(purchases, sales, itemNumber, product.id(), destination.id(), today);
        BigDecimal annualSaving = savingPerUnit != null && annual.units() != null
                ? savingPerUnit.multiply(annual.units()).setScale(2, RoundingMode.HALF_UP) : null;

        BigDecimal marginNowPct = PricingMath.marginPct(sellPrice, currentCost);
        BigDecimal marginAtTargetPct = PricingMath.marginPct(sellPrice, targetCost);
        BigDecimal marginGainPts = marginAtTargetPct != null && marginNowPct != null
                ? marginAtTargetPct.subtract(marginNowPct).setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal grossNow = sellPrice != null && currentCost != null
                ? sellPrice.subtract(currentCost).setScale(4, RoundingMode.HALF_UP) : null;
        BigDecimal grossAtTarget = sellPrice != null && targetCost != null
                ? sellPrice.subtract(targetCost).setScale(4, RoundingMode.HALF_UP) : null;

        String destinationName = storeCity(destination) + " — " + destination.subdivisionCode();
        Lane lane = LogisticsEngine.laneFor(logisticsRef, incumbentCountry != null ? incumbentCountry
                : (mine != null ? mine.country() : "USA"), region);

        List<CalcStep> steps = buildSteps(mine, region, marketLow, marketHigh, marketMedian, targetCost,
                destinationName, incumbentName, incumbentCost, isOverride);
        List<FactorWeight> weights = buildWeights(mine);

        Map<String, String> sources = new LinkedHashMap<>();
        if (currentCostSource != null) {
            sources.put("currentCost", currentCostSource);
        }
        if (sellPriceSource != null) {
            sources.put("sellPrice", sellPriceSource);
        }
        if (incumbentCostSource != null) {
            sources.put("incumbentCost", incumbentCostSource);
        }
        if (targetCost != null) {
            sources.put("targetCost", n >= 1 ? "quoted-panel" : "lane-estimate");
        }
        sources.put("annualUnits", annual.source());

        List<String> locked = new ArrayList<>();
        if (incumbentId == null) {
            locked.add("purchases");
        }
        if (currentCost == null || sellPrice == null) {
            locked.add("margin");
        }

        return new BuyRecommendation(
                itemNumber, product.description(), supplierId, supplierName, priceable,
                destinationId, destinationName, region.label(), lane,
                incumbentId, incumbentName, incumbentReason, incumbentCost, isOverride,
                sellPrice, sellPriceLocal, marginNowPct, marginAtTargetPct, marginGainPts,
                grossNow, grossAtTarget,
                mine != null ? mine.exWorksCost() : null, mine != null ? mine.freightCost() : null,
                mine != null ? mine.dutyCost() : null, currentCost, targetCost, savingPerUnit, savingPct,
                annual.units() != null ? annual.units().setScale(0, RoundingMode.HALF_UP).intValueExact() : null,
                annualSaving, quotes, marketLow, marketMedian, marketHigh,
                steps, weights, marketLow, sources, locked);
    }

    private QuoteCalc quoteFor(String itemNumber, SupplierGateway.Quote link, CatalogGateway.LogisticsRef logisticsRef,
            CatalogGateway.LaneRef region, LocalDate today, String chosenSupplierId, String incumbentId) {
        SupplierGateway.SupplierRow s = link.supplier();
        BigDecimal exWorks = link.exWorks();
        String exWorksSource = null;
        LocalDate exWorksAsOf = link.exWorksAsOf();
        if (exWorks != null) {
            exWorksSource = "purchases".equals(link.exWorksSource()) ? "purchases" : "price-list";
        } else {
            PurchaseHistory.PoStats w12 = purchases.itemSupplier(itemNumber, s.id(), Window.trailingMonths(today, 12));
            if (w12.any() && w12.lastExWorks() != null) {
                exWorks = w12.lastExWorks();
                exWorksSource = "purchases";
                exWorksAsOf = w12.lastOrder();
            }
        }

        BigDecimal freight = null;
        BigDecimal duty = null;
        BigDecimal landed = null;
        String landedSource = null;
        Integer leadTimeDays = link.leadTimeDays() != null ? link.leadTimeDays() : s.leadTimeDays();
        Lane lane = LogisticsEngine.laneFor(logisticsRef, s.country(), region);

        if (exWorks != null) {
            PurchaseHistory.PoStats w90 = purchases.itemSupplier(itemNumber, s.id(), Window.trailingDays(today, 90));
            PurchaseHistory.PoStats w12 = purchases.itemSupplier(itemNumber, s.id(), Window.trailingMonths(today, 12));
            if (w90.pos() >= 3) {
                landed = w90.avgLanded();
                landedSource = "observed-90d";
            } else if (w12.pos() >= 3) {
                landed = w12.avgLanded();
                landedSource = "observed-12m";
            } else {
                freight = exWorks.multiply(BigDecimal.valueOf(lane.freightPct() / 100.0)).setScale(4, RoundingMode.HALF_UP);
                duty = exWorks.multiply(BigDecimal.valueOf(lane.dutyPct() / 100.0)).setScale(4, RoundingMode.HALF_UP);
                landed = exWorks.add(freight).add(duty).setScale(4, RoundingMode.HALF_UP);
                landedSource = "lane-estimate";
            }
        }

        int transitDays = lane.transitDays();
        Integer totalLeadDays = leadTimeDays != null ? leadTimeDays + transitDays : null;
        boolean isCurrent = s.id().equals(chosenSupplierId);
        boolean isIncumbent = s.id().equals(incumbentId);
        return new QuoteCalc(new SupplierQuote(s.id(), s.name(), s.country(), exWorks, freight, duty, landed,
                leadTimeDays, transitDays, totalLeadDays, s.otifPct(), isCurrent, isIncumbent, exWorksSource,
                exWorksAsOf, landedSource), s.id());
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        if (sorted.isEmpty()) {
            return null;
        }
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return sorted.get(mid - 1).add(sorted.get(mid)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private static List<CalcStep> buildSteps(SupplierQuote mine, CatalogGateway.LaneRef region, BigDecimal marketLow,
            BigDecimal marketHigh, BigDecimal marketMedian, BigDecimal targetCost, String destinationName,
            String incumbentName, BigDecimal incumbentCost, boolean isOverride) {
        List<CalcStep> steps = new ArrayList<>();
        if (mine != null && mine.exWorksCost() != null) {
            steps.add(new CalcStep(isOverride ? "Quote from the supplier being evaluated" : "Quote from current supplier",
                    Js.fmtMoney(mine.exWorksCost().doubleValue()), mine.name() + ", ex-works " + mine.country(), "step"));
            if (mine.freightCost() != null) {
                steps.add(new CalcStep("Freight to " + destinationName, "+" + Js.fmtMoney(mine.freightCost().doubleValue()),
                        "Lane estimate into the " + region.label(), "step"));
            }
            if (mine.dutyCost() != null) {
                steps.add(new CalcStep("Duty", "+" + Js.fmtMoney(mine.dutyCost().doubleValue()), "Lane estimate", "step"));
            }
            if (mine.unitCost() != null) {
                steps.add(new CalcStep(isOverride ? "Landed cost if the line moved" : "Landed cost today",
                        Js.fmtMoney(mine.unitCost().doubleValue()),
                        isOverride && incumbentCost != null
                                ? "Against " + Js.fmtMoney(incumbentCost.doubleValue()) + " with " + incumbentName + " today"
                                : "What this supplier's own history says it lands at", "step"));
            }
        }
        if (marketLow != null && marketHigh != null) {
            steps.add(new CalcStep("Panel quotes, landed here",
                    Js.fmtMoney(marketLow.doubleValue()) + " - " + Js.fmtMoney(marketHigh.doubleValue()),
                    "Suppliers able to serve this item, each on its own lane into the " + region.label(), "step"));
        }
        if (marketMedian != null) {
            steps.add(new CalcStep("Market median", Js.fmtMoney(marketMedian.doubleValue()), "Middle of the panel", "step"));
        }
        if (targetCost != null) {
            steps.add(new CalcStep("Target set at", Js.fmtMoney(targetCost.doubleValue()),
                    "Best real quote plus 35% of the gap to the median - achievable, not theoretical", "result"));
        }
        return steps;
    }

    /** Real shares of the selected supplier's own landed cost: ex-works, freight+duty, the rest. Empty without a quote. */
    private static List<FactorWeight> buildWeights(SupplierQuote mine) {
        if (mine == null || mine.exWorksCost() == null || mine.unitCost() == null || mine.unitCost().signum() == 0) {
            return List.of();
        }
        double landed = mine.unitCost().doubleValue();
        double exWorksPct = Js.round1(mine.exWorksCost().doubleValue() / landed * 100);
        double freightDuty = (mine.freightCost() == null ? 0 : mine.freightCost().doubleValue())
                + (mine.dutyCost() == null ? 0 : mine.dutyCost().doubleValue());
        double freightDutyPct = Js.round1(freightDuty / landed * 100);
        double remainderPct = Js.round1(Math.max(0, 100 - exWorksPct - freightDutyPct));
        return List.of(
                new FactorWeight("Ex-works", exWorksPct, "down"),
                new FactorWeight("Freight and duty", freightDutyPct, "up"),
                new FactorWeight("Remainder", remainderPct, "down"));
    }
}
