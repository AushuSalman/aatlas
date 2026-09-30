package com.aatlas.buy.internal;

import com.aatlas.buy.BuyRecommendation;
import com.aatlas.buy.CalcStep;
import com.aatlas.buy.FactorWeight;
import com.aatlas.buy.Lane;
import com.aatlas.buy.MarketEvidence;
import com.aatlas.buy.SupplierQuote;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Anchor;
import com.aatlas.history.BuyBenchmarks;
import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.Inventory;
import com.aatlas.history.PriceLadder;
import com.aatlas.history.Reference;
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
    private final CompetitorPrices competitorPrices;
    private final Reference reference;
    private final Inventory inventory;
    private final BuyBenchmarks buyBenchmarks;
    private final AatlasClock clock;

    BuyRecommendationEngine(Catalogue catalogue, CatalogGateway catalog, SupplierGateway supplierGateway,
            PurchaseHistory purchases, SalesHistory sales, PriceLadder ladder, CompetitorPrices competitorPrices,
            Reference reference, Inventory inventory, BuyBenchmarks buyBenchmarks, AatlasClock clock) {
        this.catalogue = catalogue;
        this.catalog = catalog;
        this.supplierGateway = supplierGateway;
        this.purchases = purchases;
        this.sales = sales;
        this.ladder = ladder;
        this.competitorPrices = competitorPrices;
        this.reference = reference;
        this.inventory = inventory;
        this.buyBenchmarks = buyBenchmarks;
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
            // The rest of the panel stays in the list, unpriced: one supplier pricing the item (say a
            // listing just added from the open market) must not hide everyone else from the comparison
            // or from the RFQ that would get them to quote. No price means no part in the target.
            java.util.Set<String> linked = new java.util.HashSet<>();
            links.forEach(l -> linked.add(l.supplier().id()));
            for (SupplierGateway.SupplierRow s : supplierGateway.panel()) {
                if (linked.add(s.id())) {
                    calcs.add(new QuoteCalc(new SupplierQuote(s.id(), s.name(), s.country(), null, null, null, null,
                            s.leadTimeDays(), 0, s.leadTimeDays(), s.otifPct(), s.id().equals(chosenSupplierId),
                            s.id().equals(incumbentId), null, null, null), s.id()));
                }
            }
        }

        List<QuoteCalc> quoted = calcs.stream().filter(c -> c.quote().unitCost() != null).toList();

        // ---- market evidence: the panel's landed quotes, plus the open market - bulk lots per unit
        // and a should-cost read off competitors' shop prices - so a target exists on day one and the
        // market counts even when the panel is small.
        List<MarketEvidence.Point> points = new ArrayList<>();
        for (QuoteCalc c : quoted) {
            points.add(new MarketEvidence.Point(MarketEvidence.SUPPLIER, c.quote().name() + " quote, landed",
                    c.quote().unitCost(), landedLabel(c.quote().landedSource()), c.quote().exWorksAsOf()));
        }
        List<CompetitorPrices.Observation> shop = competitorPrices.forItem(product.id(), destination.regionKey(),
                destination.id(), today);
        BigDecimal retailLow = shop.stream().map(CompetitorPrices.Observation::price)
                .filter(p -> p != null && p.signum() > 0).min(BigDecimal::compareTo).orElse(null);
        BigDecimal retailMedian = competitorPrices.anchor(product.id(), destination.regionKey(), destination.id(), today)
                .map(Anchor::value).orElse(null);
        Reference.Benchmark benchmark = reference.benchmark(product.category(), product.subcategory());
        BigDecimal targetMarginPct = benchmark == null ? null : benchmark.targetMarginPct();
        BigDecimal shouldCost = null;
        if (retailMedian != null && targetMarginPct != null && targetMarginPct.compareTo(PricingMath.HUNDRED) < 0) {
            shouldCost = retailMedian.multiply(PricingMath.HUNDRED.subtract(targetMarginPct))
                    .divide(PricingMath.HUNDRED, 4, RoundingMode.HALF_UP);
            points.add(new MarketEvidence.Point(MarketEvidence.RETAIL_DERIVED, "Should-cost from shop prices", shouldCost,
                    "Competitors' median shop price " + Js.fmtMoney(retailMedian.doubleValue()) + " less your "
                            + product.category() + " target margin of " + targetMarginPct.stripTrailingZeros().toPlainString()
                            + "% (" + shop.size() + " shop " + (shop.size() == 1 ? "price" : "prices") + ")", today));
        }
        Optional<BuyBenchmarks.Bulk> bulk = buyBenchmarks.bulk(product.id(), today);
        bulk.ifPresent(b -> points.add(new MarketEvidence.Point(MarketEvidence.BULK_LOTS, "Bulk lots, per unit",
                b.medianPerUnit(), b.listings() + " open-market " + (b.listings() == 1 ? "lot" : "lots") + " ("
                        + b.sources() + "), median per unit; lowest " + Js.fmtMoney(b.lowPerUnit().doubleValue()),
                b.observedAt())));

        List<BigDecimal> evidence = points.stream().map(MarketEvidence.Point::value).sorted().toList();
        BigDecimal marketLow = evidence.isEmpty() ? null : evidence.get(0);
        BigDecimal marketHigh = evidence.isEmpty() ? null : evidence.get(evidence.size() - 1);
        BigDecimal marketMedian = median(evidence);
        boolean fromPanel = !quoted.isEmpty();
        boolean fromMarket = points.size() > quoted.size();
        String targetBasis = fromPanel && fromMarket ? "panel+market" : fromPanel ? "panel" : fromMarket ? "market" : null;

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

        int n = evidence.size();
        BigDecimal targetCost;
        if (n >= 2) {
            BigDecimal raw = marketLow.add(marketMedian.subtract(marketLow).multiply(new BigDecimal("0.35")));
            targetCost = currentCost != null ? PricingMath.min(currentCost, PricingMath.max(marketLow, raw)) : raw;
        } else if (n == 1) {
            BigDecimal only = evidence.get(0);
            targetCost = currentCost != null ? PricingMath.min(currentCost, only) : only;
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

        String subdivision = destination.subdivisionCode();
        String destinationName = storeCity(destination) + (subdivision == null || subdivision.isBlank() ? "" : " — " + subdivision);
        Lane lane = LogisticsEngine.laneFor(logisticsRef, incumbentCountry != null ? incumbentCountry
                : (mine != null ? mine.country() : "USA"), region);

        List<CalcStep> steps = buildSteps(mine, region, marketLow, marketHigh, marketMedian, targetCost,
                destinationName, incumbentName, incumbentCost, isOverride, points, targetBasis);
        List<MarketEvidence.Flag> flags = flags(currentCost, retailLow, shouldCost, targetMarginPct,
                bulk.map(BuyBenchmarks.Bulk::medianPerUnit).orElse(null), !points.isEmpty(), product.category());

        // ---- how many to order, and when
        Optional<Inventory.OnHand> onHand = inventory.onHand(product.id(), destination.id());
        SalesHistory.Velocity velocity = sales.velocity(product.id(), destination.id(), today);
        BigDecimal perWeek = velocity == null ? null : velocity.recentPerWeek();
        String perWeekBasis = "Sold at " + storeLabel(destination) + " over the last 90 days";
        if ((perWeek == null || perWeek.signum() <= 0) && annual.units() != null && annual.units().signum() > 0) {
            perWeek = annual.units().divide(BigDecimal.valueOf(52), 2, RoundingMode.HALF_UP);
            perWeekBasis = "A year of " + annual.source() + ", spread over 52 weeks";
        }
        Integer leadDays = mine != null ? mine.totalLeadDays() : null;
        String leadBasis = "supplier";
        if (leadDays == null) {
            leadDays = quotes.stream().map(SupplierQuote::totalLeadDays).filter(java.util.Objects::nonNull)
                    .min(Integer::compareTo).orElse(null);
            leadBasis = "panel";
        }
        String moqSupplier = supplierId;
        Integer moq = links.stream().filter(l -> l.supplier().id().equals(moqSupplier)).map(SupplierGateway.Quote::moq)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        MarketEvidence.Reorder reorder = ReorderEngine.plan(onHand.map(Inventory.OnHand::units).orElse(null),
                onHand.map(Inventory.OnHand::asOf).orElse(null), perWeek, perWeekBasis, leadDays, leadBasis, moq, today);
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
            sources.put("targetCost", "panel".equals(targetBasis) ? "quoted-panel" : targetBasis);
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
                steps, weights, marketLow, sources, locked,
                points, targetBasis, retailLow, shouldCost, flags, reorder);
    }

    private static String landedLabel(String landedSource) {
        if (landedSource == null) {
            return "Landed at your branch";
        }
        return switch (landedSource) {
            case "observed-90d" -> "What you paid landed, last 90 days";
            case "observed-12m" -> "What you paid landed, last 12 months";
            default -> "Supplier price plus estimated freight and duty";
        };
    }

    /**
     * What a buyer should know about today's cost, worst first: paying more than a shop charges
     * (bad), more than the cost that keeps the category's margin at the market price (warn), more than
     * bulk lots go for (warn); else in line (good); no market evidence at all (info).
     */
    static List<MarketEvidence.Flag> flags(BigDecimal currentCost, BigDecimal retailLow, BigDecimal shouldCost,
            BigDecimal targetMarginPct, BigDecimal bulkMedian, boolean anyEvidence, String category) {
        List<MarketEvidence.Flag> out = new ArrayList<>();
        if (!anyEvidence) {
            out.add(new MarketEvidence.Flag("info", "No market prices yet. Run \"Check retail and bulk\" or fetch "
                    + "competitor prices, or add supplier prices, to see whether what you pay is fair."));
            return out;
        }
        if (currentCost == null) {
            return out;
        }
        if (retailLow != null && currentCost.compareTo(retailLow) > 0) {
            out.add(new MarketEvidence.Flag("bad", "You pay " + Js.fmtMoney(currentCost.doubleValue())
                    + " - more than the lowest shop price, " + Js.fmtMoney(retailLow.doubleValue())
                    + ". A trade buyer should pay well under retail."));
        } else if (shouldCost != null && currentCost.compareTo(shouldCost) > 0) {
            out.add(new MarketEvidence.Flag("warn", "At competitors' shop prices you'd earn less than your "
                    + category + " target margin of " + targetMarginPct.stripTrailingZeros().toPlainString()
                    + "%. The cost that keeps it is " + Js.fmtMoney(shouldCost.doubleValue()) + "."));
        }
        if (bulkMedian != null
                && currentCost.compareTo(bulkMedian.multiply(new BigDecimal("1.05"))) > 0) {
            BigDecimal under = PricingMath.pct(currentCost.subtract(bulkMedian), currentCost);
            out.add(new MarketEvidence.Flag("warn", "Bulk lots go for " + Js.fmtMoney(bulkMedian.doubleValue())
                    + " a unit on the open market" + (under == null ? "" : " - "
                            + under.setScale(0, RoundingMode.HALF_UP).toPlainString() + "% under what you pay") + "."));
        }
        if (out.isEmpty()) {
            out.add(new MarketEvidence.Flag("good", "What you pay is in line with the market evidence on file."));
        }
        return out;
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
            String incumbentName, BigDecimal incumbentCost, boolean isOverride, List<MarketEvidence.Point> points,
            String targetBasis) {
        List<CalcStep> steps = new ArrayList<>();
        boolean withMarket = targetBasis != null && targetBasis.contains("market");
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
        // Open-market evidence, each on its own line so the buyer sees where the target came from.
        for (MarketEvidence.Point p : points) {
            if (!MarketEvidence.SUPPLIER.equals(p.source())) {
                steps.add(new CalcStep(p.label(), Js.fmtMoney(p.value().doubleValue()), p.detail(), "step"));
            }
        }
        if (marketLow != null && marketHigh != null) {
            steps.add(new CalcStep(withMarket ? "Market evidence, per unit" : "Panel quotes, landed here",
                    Js.fmtMoney(marketLow.doubleValue()) + " - " + Js.fmtMoney(marketHigh.doubleValue()),
                    withMarket ? points.size() + " comparable prices: your suppliers' quotes and the open market"
                            : "Suppliers able to serve this item, each on its own lane into the " + region.label(),
                    "step"));
        }
        if (marketMedian != null) {
            steps.add(new CalcStep("Market median", Js.fmtMoney(marketMedian.doubleValue()),
                    withMarket ? "Middle of the evidence" : "Middle of the panel", "step"));
        }
        if (targetCost != null) {
            steps.add(new CalcStep("Target set at", Js.fmtMoney(targetCost.doubleValue()),
                    (withMarket ? "Lowest comparable price" : "Best real quote")
                            + " plus 35% of the gap to the median, never above what you pay today", "result"));
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
