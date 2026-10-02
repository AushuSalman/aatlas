package com.aatlas.buy.internal;

import com.aatlas.buy.BuyModelSummary;
import com.aatlas.buy.BuyRecommendation;
import com.aatlas.buy.CalcStep;
import com.aatlas.buy.FactorWeight;
import com.aatlas.buy.Lane;
import com.aatlas.buy.MarketEvidence;
import com.aatlas.buy.SupplierQuote;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.Anchor;
import com.aatlas.history.BuyBenchmarks;
import com.aatlas.history.Catalogue;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.DecisionPatterns.Acceptance;
import com.aatlas.history.Inventory;
import com.aatlas.history.PriceLadder;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PricingModel;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.Reference;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The landed-cost panel for one (item, destination, supplier): spec-A S2 "buy" / S3.5. Every
 * figure is either read through {@code history} (incumbent, quotes, cost, sell price) or
 * derived from those real numbers; a step with no input is skipped, never zeroed, and
 * {@link BuyRecommendation#incumbentSupplierId()} is null with a reason when there is
 * genuinely nobody to compare against.
 *
 * <p>The tenant's buying model ({@link PricingModel}, buy side) decides which of those steps
 * run: who the reference supplier is, whether real landed costs are trusted, what counts as
 * market evidence, where the target sits and how it is phased in and capped
 * ({@link BuyTargetChain}), whether today's cost is checked against the market, and whether
 * reorder advice is given. Every model step is one line of {@link BuyRecommendation#steps()}
 * with its kind {@code applied}, {@code skipped} or {@code off}.
 */
@Component
class BuyRecommendationEngine {

    private static final Logger log = LoggerFactory.getLogger(BuyRecommendationEngine.class);

    /** How many recorded decisions the lean is learned from, per basis. */
    static final int LEARNING_ROWS_ITEM = 200;
    static final int LEARNING_ROWS_TENANT = 300;

    /** The bulk-lot tolerance the market check used before it became a knob. */
    static final BigDecimal DEFAULT_BULK_TOLERANCE_PCT = BigDecimal.valueOf(5);

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
    private final DealSummaries deals;
    private final com.aatlas.supplymodel.DeliveryModels deliveryModels;
    private final AatlasClock clock;

    BuyRecommendationEngine(Catalogue catalogue, CatalogGateway catalog, SupplierGateway supplierGateway,
            PurchaseHistory purchases, SalesHistory sales, PriceLadder ladder, CompetitorPrices competitorPrices,
            Reference reference, Inventory inventory, BuyBenchmarks buyBenchmarks, DealSummaries deals,
            com.aatlas.supplymodel.DeliveryModels deliveryModels, AatlasClock clock) {
        this.deliveryModels = deliveryModels;
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
        this.deals = deals;
        this.clock = clock;
    }

    /**
     * The quote with its lead time and on-time rate taken from the tenant's delivery model, where the buying
     * model allows it ({@code buy.reliability} and {@code buy.reliability.trainedModel} on) and the model has
     * proven itself on this supplier: it beat the supplier's own record on its held-out orders. The forecast is
     * for the supplier's usual order quantity of the item into this branch, against the lead time the supplier
     * quotes. Anything else, or any failure, leaves the supplier's averages.
     */
    private SupplierQuote withDeliveryModel(SupplierQuote q, String itemNumber, String destinationId,
            PricingModel.Config cfg) {
        if (!cfg.on(PricingModel.BUY_RELIABILITY) || !cfg.on(PricingModel.BUY_RELIABILITY_TRAINED_MODEL)) {
            return q;
        }
        try {
            java.util.Optional<com.aatlas.supplymodel.DeliveryModels.Forecast> f = deliveryModels.forecast(q.supplierId(),
                    itemNumber, destinationId, 0, q.totalLeadDays());
            if (f.isEmpty() && q.name() != null) {
                f = deliveryModels.forecast(q.name(), itemNumber, destinationId, 0, q.totalLeadDays());
            }
            com.aatlas.supplymodel.DeliveryModels.Forecast m = f
                    .filter(x -> x.usable() && x.lateProbability() != null && x.leadDays() != null).orElse(null);
            if (m == null) {
                return q;
            }
            BigDecimal hundred = BigDecimal.valueOf(100);
            BigDecimal otif = hundred.subtract(m.lateProbability().multiply(hundred)).setScale(1, RoundingMode.HALF_UP);
            Integer lead = Integer.valueOf(m.leadDays().setScale(0, RoundingMode.HALF_UP).intValue());
            return new SupplierQuote(q.supplierId(), q.name(), q.country(), q.exWorksCost(), q.freightCost(), q.dutyCost(),
                    q.unitCost(), q.leadTimeDays(), q.transitDays(), lead, otif, q.isCurrent(), q.isIncumbent(),
                    q.exWorksSource(), q.exWorksAsOf(), q.landedSource(), "delivery-model");
        } catch (RuntimeException ex) {
            return q;
        }
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
        PricingModel.Config cfg = reference.pricingModel();
        List<CalcStep> steps = new ArrayList<>();

        List<SupplierGateway.Quote> links = supplierGateway.quotesFor(product.id());
        boolean noLinksAtAll = links.isEmpty();
        List<SupplierGateway.SupplierRow> quotablePanel = noLinksAtAll
                ? supplierGateway.panel()
                : List.of();

        // ---- 1. the reference supplier: who you actually buy from, or the cheapest quote ----
        boolean incumbentOn = cfg.on(PricingModel.BUY_INCUMBENT);
        Optional<PurchaseHistory.SupplierShare> incumbentShare = incumbentOn
                ? purchases.incumbent(itemNumber, today) : Optional.empty();
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
            steps.add(new CalcStep("Reference supplier", incumbentName,
                    share.sharePct() != null
                            ? incumbentName + " has " + share.sharePct().setScale(0, RoundingMode.HALF_UP).toPlainString()
                                    + "% of your spend on this item in the last twelve months."
                            : incumbentName + " has the largest share of your spend on this item in the last twelve months.",
                    BuyTargetChain.APPLIED));
        } else if (!links.isEmpty()) {
            SupplierGateway.Quote cheapest = links.stream()
                    .filter(l -> l.exWorks() != null)
                    .min((a, b) -> a.exWorks().compareTo(b.exWorks()))
                    .orElse(links.get(0));
            incumbentId = cheapest.supplier().id();
            incumbentName = cheapest.supplier().name();
            incumbentCountry = cheapest.supplier().country();
            incumbentCostSource = incumbentOn ? "supplier-list" : "cheapest-quote";
            steps.add(incumbentOn
                    ? new CalcStep("Reference supplier", incumbentName, "No purchase history for this item yet; "
                            + incumbentName + " is the cheapest listed supplier.", BuyTargetChain.SKIPPED)
                    : new CalcStep("Reference supplier", incumbentName, incumbentName + ", the cheapest quoted supplier, "
                            + "is the reference; who you actually buy from is not used by your buying model.",
                            BuyTargetChain.OFF));
        } else {
            incumbentReason = incumbentOn
                    ? "No purchase history for this item — pick a supplier to compare."
                    : "No supplier quotes on file for this item — pick a supplier to compare.";
            steps.add(new CalcStep("Reference supplier", "—", incumbentOn
                    ? "No purchase history and no supplier quotes on file for this item."
                    : "No supplier quotes on file; who you actually buy from is not used by your buying model.",
                    incumbentOn ? BuyTargetChain.SKIPPED : BuyTargetChain.OFF));
        }

        String chosenSupplierId = supplierIdOverride != null && !supplierIdOverride.isBlank()
                ? supplierIdOverride
                : incumbentId;

        CatalogGateway.LogisticsRef logisticsRef = catalog.logistics();
        CatalogGateway.LaneRef region = LogisticsEngine.regionForState(logisticsRef, destination.subdivisionCode());

        // ---- 2. every supplier's quote, landed here ----------------------------------------
        boolean observedOn = cfg.on(PricingModel.BUY_OBSERVED_LANDED);
        int minOrders = (int) cfg.number(PricingModel.BUY_OBSERVED_LANDED_MIN_ORDERS);
        List<QuoteCalc> calcs = new ArrayList<>();
        if (noLinksAtAll) {
            for (SupplierGateway.SupplierRow s : quotablePanel) {
                calcs.add(new QuoteCalc(new SupplierQuote(s.id(), s.name(), s.country(), null, null, null, null,
                        s.leadTimeDays(), 0, s.leadTimeDays(), s.otifPct(), s.id().equals(chosenSupplierId),
                        s.id().equals(incumbentId), null, null, null), s.id()));
            }
        } else {
            for (SupplierGateway.Quote link : links) {
                calcs.add(quoteFor(itemNumber, link, logisticsRef, region, today, chosenSupplierId, incumbentId,
                        observedOn, minOrders));
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

        // Where the delivery model has proven itself on a supplier, its lead time and on-time rate for a
        // typical order of this item into this branch replace the supplier's averages on the quote, so the
        // comparison, the scenarios and the reorder timing all read the same forecast.
        List<SupplierQuote> quotes = calcs.stream()
                .map(c -> withDeliveryModel(c.quote(), itemNumber, destinationId, cfg))
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
        if (incumbentCost == null && "cheapest-quote".equals(incumbentCostSource)) {
            String ref = incumbentId;
            incumbentCost = quotes.stream().filter(q -> q.supplierId().equals(ref)).map(SupplierQuote::unitCost)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        }

        String subdivision = destination.subdivisionCode();
        String destinationName = storeCity(destination) + (subdivision == null || subdivision.isBlank() ? "" : " — " + subdivision);
        steps.addAll(quoteSteps(mine, region, destinationName, incumbentName, incumbentCost, isOverride));

        long observedCount = quoted.stream()
                .filter(c -> c.quote().landedSource() != null && c.quote().landedSource().startsWith("observed"))
                .count();
        if (!observedOn) {
            steps.add(new CalcStep("Landed cost from your orders", "—",
                    "Landed cost is always the quote plus freight and duty for the lane.", BuyTargetChain.OFF));
        } else if (quoted.isEmpty()) {
            steps.add(new CalcStep("Landed cost from your orders", "—", "No supplier quotes to land.",
                    BuyTargetChain.SKIPPED));
        } else if (observedCount == 0) {
            steps.add(new CalcStep("Landed cost from your orders", "—",
                    "Fewer than " + minOrders + " received orders per supplier in the last twelve months, so landed "
                            + "cost is the quote plus freight and duty for the lane.", BuyTargetChain.SKIPPED));
        } else {
            steps.add(new CalcStep("Landed cost from your orders", observedCount + " of " + quoted.size(),
                    observedCount + " of " + quoted.size() + (quoted.size() == 1 ? " supplier's" : " suppliers'")
                            + " landed cost comes from your received orders (at least " + minOrders
                            + " in the window); the rest from the lane estimate.", BuyTargetChain.APPLIED));
        }

        // ---- 3. market evidence: the panel's landed quotes, plus the open market - bulk lots per
        // unit and a should-cost read off competitors' shop prices - so a target exists on day one
        // and the market counts even when the panel is small. The model decides which count.
        List<MarketEvidence.Point> candidates = new ArrayList<>();
        for (QuoteCalc c : quoted) {
            candidates.add(new MarketEvidence.Point(MarketEvidence.SUPPLIER, c.quote().name() + " quote, landed",
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
            candidates.add(new MarketEvidence.Point(MarketEvidence.RETAIL_DERIVED, "Should-cost from shop prices", shouldCost,
                    "Competitors' median shop price " + Js.fmtMoney(retailMedian.doubleValue()) + " less your "
                            + product.category() + " target margin of " + targetMarginPct.stripTrailingZeros().toPlainString()
                            + "% (" + shop.size() + " shop " + (shop.size() == 1 ? "price" : "prices") + ")", today));
        }
        Optional<BuyBenchmarks.Bulk> bulk = buyBenchmarks.bulk(product.id(), today);
        bulk.ifPresent(b -> candidates.add(new MarketEvidence.Point(MarketEvidence.BULK_LOTS, "Bulk lots, per unit",
                b.medianPerUnit(), b.listings() + " open-market " + (b.listings() == 1 ? "lot" : "lots") + " ("
                        + b.sources() + "), median per unit; lowest " + Js.fmtMoney(b.lowPerUnit().doubleValue()),
                b.observedAt())));

        // ---- 4. what you pay and charge today ---------------------------------------------
        Optional<Resolved> costResolved = ladder.cost(product.id(), destination.id(), today);
        BigDecimal currentCost = costResolved.map(Resolved::value).orElse(null);
        String currentCostSource = costResolved.map(Resolved::source).orElse(null);

        Optional<Resolved> priceResolved = ladder.currentPrice(product.id(), destination.id(), today);
        BigDecimal sellPrice = priceResolved.map(Resolved::value).orElse(null);
        String sellPriceSource = priceResolved.map(Resolved::source).orElse(null);
        boolean sellPriceLocal = priceResolved.isPresent() && !Resolved.SALES_ITEM_12M.equals(priceResolved.get().source());
        boolean priceable = sellPrice != null;

        // ---- 5. the target, as the model composes it --------------------------------------
        BuyTargetChain.Track track = track(itemNumber, destinationId, currentCost, today, cfg);
        BuyTargetChain.Result chain = BuyTargetChain.compose(candidates, currentCost, track, cfg, region.label());
        List<MarketEvidence.Point> points = chain.points();
        BigDecimal marketLow = chain.marketLow();
        BigDecimal marketHigh = chain.marketHigh();
        BigDecimal marketMedian = chain.marketMedian();
        String targetBasis = chain.targetBasis();
        BigDecimal targetCost = chain.target();
        steps.addAll(chain.steps());

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

        Lane lane = LogisticsEngine.laneFor(logisticsRef, incumbentCountry != null ? incumbentCountry
                : (mine != null ? mine.country() : "USA"), region);

        // ---- 6. the market check on what you pay today -------------------------------------
        List<MarketEvidence.Flag> flags;
        if (!cfg.on(PricingModel.BUY_FLAGS)) {
            flags = List.of();
            steps.add(new CalcStep("Market check", "—",
                    "What you pay is not checked against the market by your buying model.", BuyTargetChain.OFF));
        } else {
            BigDecimal tolerance = cfg.value(PricingModel.BUY_FLAGS_BULK_TOLERANCE);
            flags = flags(currentCost, retailLow, shouldCost, targetMarginPct,
                    bulk.map(BuyBenchmarks.Bulk::medianPerUnit).orElse(null), !points.isEmpty(), product.category(),
                    tolerance);
            String checked = "Checked what you pay against the lowest shop price, the should-cost and bulk-lot prices ("
                    + tolerance.stripTrailingZeros().toPlainString() + "% tolerance above bulk lots).";
            if (flags.isEmpty()) {
                steps.add(new CalcStep("Market check", "—", "No cost on file to check against the market.",
                        BuyTargetChain.SKIPPED));
            } else if ("info".equals(flags.get(0).level())) {
                steps.add(new CalcStep("Market check", "—", "No market prices on file to check against yet.",
                        BuyTargetChain.SKIPPED));
            } else {
                steps.add(new CalcStep("Market check", flagsHeadline(flags), checked, BuyTargetChain.APPLIED));
            }
        }

        // ---- 7. how many to order, and when -----------------------------------------------
        MarketEvidence.Reorder reorder = null;
        if (!cfg.on(PricingModel.BUY_REORDER)) {
            steps.add(new CalcStep("Reorder advice", "—", "Reorder advice is not used by your buying model.",
                    BuyTargetChain.OFF));
        } else {
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
            reorder = ReorderEngine.plan(onHand.map(Inventory.OnHand::units).orElse(null),
                    onHand.map(Inventory.OnHand::asOf).orElse(null), perWeek, perWeekBasis, leadDays, leadBasis, moq,
                    today, ReorderEngine.Settings.of(cfg));
            steps.add(new CalcStep("Reorder advice", reorderHeadline(reorder.status()), reorder.summary(),
                    BuyTargetChain.APPLIED));
        }
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

        BuyModelSummary model = null;
        if (targetCost != null) {
            int[] toggles = cfg.toggleCount(PricingModel.Side.BUY);
            BigDecimal fullTarget = chain.fullTarget() != null && chain.fullTarget().compareTo(targetCost) != 0
                    ? chain.fullTarget() : null;
            model = new BuyModelSummary(toggles[0], toggles[1], chain.maturity(), fullTarget, chain.flags());
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
                points, targetBasis, retailLow, shouldCost, flags, reorder, model);
    }

    /**
     * The pair's decision history, in the shape the chain reads: how many buy decisions were
     * already recorded for this item at this branch (the phase-in's n) and the lean the past
     * decisions show. Only read when a step that needs it is on. A failing read - a tenant
     * whose decision tables are not there yet - degrades to no track record with a warning,
     * never a failed recommendation.
     */
    private BuyTargetChain.Track track(String item, String storeCode, BigDecimal currentCost, LocalDate today,
            PricingModel.Config cfg) {
        boolean learningOn = cfg.on(PricingModel.BUY_LEARNING);
        boolean phaseInOn = cfg.on(PricingModel.BUY_PHASE_IN) && currentCost != null;
        if (!learningOn && !phaseInOn) {
            return BuyTargetChain.Track.of(0, null);
        }
        try {
            long priorApplied = phaseInOn ? deals.priorApplied(DealSummaries.BUY, item, storeCode) : 0;
            DecisionPatterns.Learning learning = null;
            if (learningOn) {
                LocalDate since = today.minusDays((long) cfg.number(PricingModel.BUY_LEARNING_WINDOW_DAYS));
                List<Acceptance> rows = new ArrayList<>();
                if (storeCode != null) {
                    addAcceptance(rows, deals.acceptance(DealSummaries.BUY, item, storeCode, since, LEARNING_ROWS_ITEM),
                            Acceptance.ITEM_STORE);
                }
                addAcceptance(rows, deals.acceptance(DealSummaries.BUY, item, null, since, LEARNING_ROWS_ITEM),
                        Acceptance.ITEM);
                addAcceptance(rows, deals.acceptance(DealSummaries.BUY, null, null, since, LEARNING_ROWS_TENANT),
                        Acceptance.TENANT);
                learning = DecisionPatterns.learn(rows, today, cfg, PricingModel.Side.BUY);
            }
            return BuyTargetChain.Track.of(priorApplied, learning);
        } catch (RuntimeException ex) {
            log.warn("Decision history unavailable for {}@{} ({}); targeting without a track record", item,
                    storeCode == null ? "-" : storeCode, ex.getMessage());
            return BuyTargetChain.Track.none();
        }
    }

    private static void addAcceptance(List<Acceptance> out, List<DealSummaries.Acceptance> rows, String basis) {
        for (DealSummaries.Acceptance a : rows) {
            out.add(new Acceptance(a.date(), a.suggested(), a.actual(), basis));
        }
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

    private static String flagsHeadline(List<MarketEvidence.Flag> flags) {
        long warnings = flags.stream().filter(f -> "warn".equals(f.level())).count();
        boolean bad = flags.stream().anyMatch(f -> "bad".equals(f.level()));
        if (bad) {
            return "Over retail";
        }
        if (warnings > 0) {
            return warnings + (warnings == 1 ? " warning" : " warnings");
        }
        return "In line";
    }

    private static String reorderHeadline(String status) {
        return switch (status == null ? "" : status) {
            case "order-now" -> "Order now";
            case "order-soon" -> "Order soon";
            case "ok" -> "No order needed yet";
            case "overstocked" -> "Overstocked";
            case "no-sales" -> "No sales yet";
            case "no-stock" -> "No stock figure";
            default -> status;
        };
    }

    /**
     * What a buyer should know about today's cost, worst first: paying more than a shop charges
     * (bad), more than the cost that keeps the category's margin at the market price (warn), more than
     * bulk lots go for (warn); else in line (good); no market evidence at all (info). With the
     * market check's original tolerance above bulk lots.
     */
    static List<MarketEvidence.Flag> flags(BigDecimal currentCost, BigDecimal retailLow, BigDecimal shouldCost,
            BigDecimal targetMarginPct, BigDecimal bulkMedian, boolean anyEvidence, String category) {
        return flags(currentCost, retailLow, shouldCost, targetMarginPct, bulkMedian, anyEvidence, category,
                DEFAULT_BULK_TOLERANCE_PCT);
    }

    /** @param bulkTolerancePct how far above the bulk-lot median today's cost may sit before it is flagged */
    static List<MarketEvidence.Flag> flags(BigDecimal currentCost, BigDecimal retailLow, BigDecimal shouldCost,
            BigDecimal targetMarginPct, BigDecimal bulkMedian, boolean anyEvidence, String category,
            BigDecimal bulkTolerancePct) {
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
        BigDecimal tolerance = bulkTolerancePct == null ? DEFAULT_BULK_TOLERANCE_PCT : bulkTolerancePct;
        BigDecimal bulkCeiling = bulkMedian == null ? null
                : bulkMedian.multiply(BigDecimal.ONE.add(tolerance.divide(PricingMath.HUNDRED, 6, RoundingMode.HALF_UP)));
        if (bulkCeiling != null && currentCost.compareTo(bulkCeiling) > 0) {
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

    /**
     * @param observedLanded whether a supplier's own received orders may stand in for the lane estimate
     * @param minOrders      how many received orders in the window it takes before they do
     */
    private QuoteCalc quoteFor(String itemNumber, SupplierGateway.Quote link, CatalogGateway.LogisticsRef logisticsRef,
            CatalogGateway.LaneRef region, LocalDate today, String chosenSupplierId, String incumbentId,
            boolean observedLanded, int minOrders) {
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
            if (observedLanded) {
                PurchaseHistory.PoStats w90 = purchases.itemSupplier(itemNumber, s.id(), Window.trailingDays(today, 90));
                if (w90.pos() >= minOrders) {
                    landed = w90.avgLanded();
                    landedSource = "observed-90d";
                } else {
                    PurchaseHistory.PoStats w12 = purchases.itemSupplier(itemNumber, s.id(), Window.trailingMonths(today, 12));
                    if (w12.pos() >= minOrders) {
                        landed = w12.avgLanded();
                        landedSource = "observed-12m";
                    }
                }
            }
            if (landed == null) {
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

    /** The selected supplier's own quote, landed: the lines the derivation opens with. */
    private static List<CalcStep> quoteSteps(SupplierQuote mine, CatalogGateway.LaneRef region, String destinationName,
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
