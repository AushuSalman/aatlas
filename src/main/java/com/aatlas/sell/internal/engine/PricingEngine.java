package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.history.Anchor;
import com.aatlas.history.Catalogue.ProductRef;
import com.aatlas.history.Catalogue.StoreRef;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.CompetitorPrices.Observation;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.DecisionPatterns.Acceptance;
import com.aatlas.history.Inventory;
import com.aatlas.history.PriceLadder;
import com.aatlas.history.PriceList;
import com.aatlas.history.PricingMath;
import com.aatlas.history.PricingMath.Step;
import com.aatlas.history.PricingMath.Track;
import com.aatlas.history.Reference;
import com.aatlas.history.Resolved;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesHistory.Bucket;
import com.aatlas.history.SalesHistory.PeerBand;
import com.aatlas.history.SalesHistory.PriceBand;
import com.aatlas.history.SalesHistory.Velocity;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Stats;
import com.aatlas.history.Window;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import com.aatlas.sell.internal.dto.PricingDtos.BenchmarksDto;
import com.aatlas.sell.internal.dto.PricingDtos.CalcStepDto;
import com.aatlas.sell.internal.dto.PricingDtos.CompetitorDto;
import com.aatlas.sell.internal.dto.PricingDtos.DemandInfoDto;
import com.aatlas.sell.internal.dto.PricingDtos.FactorWeightDto;
import com.aatlas.sell.internal.dto.PricingDtos.ModelSummaryDto;
import com.aatlas.sell.internal.dto.PricingDtos.PriceBandDto;
import com.aatlas.sell.internal.dto.PricingDtos.PriceTierDto;
import com.aatlas.sell.internal.dto.PricingDtos.SellDerivationDto;
import com.aatlas.sell.internal.engine.PricingTypes.Competitor;
import com.aatlas.sell.internal.engine.PricingTypes.DemandModel;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The one place an (item, store) pair's price picture is resolved: cost, current price and
 * market anchor from {@code history.PriceLadder}, the recommendation chain from {@code
 * history.PricingMath}, everything else (peer band, competitors, demand, commodity,
 * elasticity, inventory) from the rest of {@code history}. Every other sell engine calls
 * {@link #getPricingModel} rather than reading {@code history} a second time, so the number
 * and its label agree on every screen.
 */
@Component
public class PricingEngine {

    private static final Logger log = LoggerFactory.getLogger(PricingEngine.class);

    /** The tenant's pricing model, {@code history.PricingModel}, distinct from this package's {@link PricingModel} record. */
    private static final String LEARNING = com.aatlas.history.PricingModel.LEARNING;
    private static final String LEARNING_STRATEGY = com.aatlas.history.PricingModel.LEARNING_STRATEGY;
    private static final String LEARNING_WINDOW_DAYS = com.aatlas.history.PricingModel.LEARNING_WINDOW_DAYS;
    private static final String LOCAL_MARKET = com.aatlas.history.PricingModel.LOCAL_MARKET;
    private static final String LOCAL_MARKET_WEIGHT = com.aatlas.history.PricingModel.LOCAL_MARKET_WEIGHT;

    /** Fewest bulk picks of one strategy, and its share of all picks, before it counts as a habit. */
    static final int HABIT_MIN_PICKS = 3;
    static final double HABIT_MIN_SHARE_PCT = 60;

    /** How many recorded decisions the lean is learned from, per basis. */
    static final int LEARNING_ROWS_ITEM = 200;
    static final int LEARNING_ROWS_TENANT = 300;

    private final CatalogGateway catalog;
    private final SalesHistory sales;
    private final PriceLadder ladder;
    private final Inventory inventory;
    private final CompetitorPrices competitorPrices;
    private final Reference reference;
    private final PriceList priceList;
    private final DealSummaries deals;
    private final com.aatlas.decisions.DecisionOutcomes outcomes;
    private final AatlasClock clock;

    public PricingEngine(CatalogGateway catalog, SalesHistory sales, PriceLadder ladder, Inventory inventory,
            CompetitorPrices competitorPrices, Reference reference, PriceList priceList, DealSummaries deals,
            com.aatlas.decisions.DecisionOutcomes outcomes, AatlasClock clock) {
        this.catalog = catalog;
        this.outcomes = outcomes;
        this.sales = sales;
        this.ladder = ladder;
        this.inventory = inventory;
        this.competitorPrices = competitorPrices;
        this.reference = reference;
        this.priceList = priceList;
        this.deals = deals;
        this.clock = clock;
    }

    private static String domainOf(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(sourceUrl).getHost();
            return host != null ? host : sourceUrl;
        } catch (RuntimeException ex) {
            return sourceUrl;
        }
    }

    public PricingModel getPricingModel(String item, String storeId) {
        LocalDate today = clock.today();
        Optional<ProductRef> productOpt = catalog.findProduct(item);
        boolean hasStoreId = storeId != null && !storeId.isBlank();
        Optional<StoreRef> storeOpt = hasStoreId ? catalog.findStore(storeId) : Optional.empty();

        if (productOpt.isEmpty()) {
            return emptyModel(item, storeId);
        }
        ProductRef product = productOpt.get();
        StoreRef store = storeOpt.orElse(null);
        UUID productId = product.id();
        UUID storeUuid = store != null ? store.id() : null;

        List<String> locked = new ArrayList<>();

        Optional<Resolved> currentPriceR = ladder.currentPrice(productId, storeUuid, today);
        Optional<Resolved> costR = ladder.cost(productId, storeUuid, today);
        Optional<Anchor> anchorR = ladder.anchor(productId, storeUuid, today);

        boolean priceable = currentPriceR.map(r -> r.value() != null && r.value().signum() > 0).orElse(false);
        if (costR.isEmpty()) {
            locked.add("margin");
        }

        Window w12 = Window.trailingMonths(today, 12);
        SalesStats itemStoreStats = storeUuid != null ? sales.itemStore(productId, storeUuid, w12)
                : sales.item(productId, w12);
        BigDecimal ownRef = itemStoreStats.lastPrice();

        Velocity velocity = sales.velocity(productId, storeUuid, today);
        PricingMath.Demand demand = PricingMath.demand(velocity);
        DemandModel demandModel = demand == null ? null : toDemandModel(demand);
        if (demand == null) {
            locked.add("demand");
        }

        Reference.Guardrails guardrails = reference.guardrails();

        boolean storeHeavy = storeUuid != null && itemStoreStats.txns() >= 8;
        Optional<PriceBand> band = storeHeavy ? sales.priceBandAtStore(productId, storeUuid, w12)
                : sales.priceBand(productId, w12);
        BigDecimal bandQ1 = band.map(PriceBand::q1).orElse(null);
        List<Bucket> buckets = band.map(PriceBand::buckets).orElse(List.of());

        Optional<PeerBand> peer = storeUuid != null ? sales.peerBand(productId, storeUuid, w12) : Optional.empty();
        BigDecimal peerQ1 = peer.map(PeerBand::q1).orElse(null);
        BigDecimal peerQ2 = peer.map(PeerBand::q2).orElse(null);
        BigDecimal peerQ3 = peer.map(PeerBand::q3).orElse(null);
        int peerStores = peer.map(PeerBand::stores).orElse(0);

        String regionKey = store != null ? store.regionKey() : null;
        List<Observation> observations = competitorPrices.forItem(productId, regionKey, storeUuid, today);
        BigDecimal currentPriceValue = currentPriceR.map(Resolved::value).orElse(null);
        List<Competitor> competitors = new ArrayList<>();
        for (Observation o : observations) {
            BigDecimal delta = currentPriceValue == null ? null : o.price().subtract(currentPriceValue);
            competitors.add(new Competitor(o.competitor(), domainOf(o.sourceUrl()), o.price(), delta));
        }
        if (competitors.isEmpty()) {
            locked.add("competitors");
        }
        Optional<Anchor> competitorAnchor = competitorPrices.anchor(productId, regionKey, storeUuid, today);
        BigDecimal competitorMedian = competitorAnchor.map(Anchor::value).orElse(null);

        CommodityTrend commodity = product.commodity() == null ? null : catalog.commodityTrend(product.commodity());
        BigDecimal commodityPct90 = commodity == null ? null : BigDecimal.valueOf(commodity.pct90());
        String commodityLabel = commodity == null ? null : commodity.label();
        LocalDate commodityAsOf = commodity == null ? null : commodity.asOf();
        String commoditySource = commodity == null ? null : commodity.provenance();

        BigDecimal rpp = store != null ? store.rpp() : null;

        // What applied prices actually did for this item (measured sales before against after) is folded
        // into the sensitivity: the model learns from outcomes, not only from what was chosen.
        SalesHistory.Elasticity elasticity = outcomes.blend(sales.elasticity(productId, storeUuid, today), item, today);
        BigDecimal beta = elasticity.coefficient();

        Window w90 = Window.trailingDays(today, 90);
        SalesStats w90Stats = storeUuid != null ? sales.itemStore(productId, storeUuid, w90) : sales.item(productId, w90);
        SalesStats w90pStats = storeUuid != null ? sales.itemStore(productId, storeUuid, w90.prior())
                : sales.item(productId, w90.prior());
        Window w30 = Window.trailingDays(today, 30);
        SalesStats w30Stats = storeUuid != null ? sales.itemStore(productId, storeUuid, w30) : sales.item(productId, w30);

        Optional<Inventory.OnHand> onHand = inventory.onHand(productId, storeUuid);
        BigDecimal onHandUnits = null;
        LocalDate inventoryAsOf = null;
        boolean stale = false;
        if (onHand.isPresent()) {
            onHandUnits = onHand.get().units();
            inventoryAsOf = onHand.get().asOf();
            stale = onHand.get().stale(today);
        }
        if (onHand.isEmpty() || stale) {
            locked.add("inventory");
        }

        // -- the chain, as the tenant's model configures it ---------------------------------
        com.aatlas.history.PricingModel.Config cfg = reference.pricingModel();
        String storeCode = store != null ? store.storeCode() : null;
        LocalDate lastSale = storeUuid == null ? null : sales.lastSale(productId, storeUuid).orElse(null);
        Reference.Benchmark benchmark = reference.benchmark(product.category(), product.subcategory());
        BigDecimal benchmarkTargetMarginPct = benchmark == null ? null : benchmark.targetMarginPct();
        Track track = track(item, storeCode, productId, storeUuid, today, cfg);
        String rampSalt = item + "|" + (hasStoreId ? storeId : "") + "|" + YearMonth.from(today);

        BigDecimal competitorLow = observations.stream().map(Observation::price).filter(p -> p != null && p.signum() > 0)
                .min(BigDecimal::compareTo).orElse(null);
        BigDecimal competitorHigh = observations.stream().map(Observation::price).filter(p -> p != null && p.signum() > 0)
                .max(BigDecimal::compareTo).orElse(null);
        PricingMath.Inputs inputs = new PricingMath.Inputs(costR.map(Resolved::value).orElse(null),
                currentPriceValue, ownRef, anchorR.orElse(null), competitorMedian, observations.size(),
                competitorLow, competitorHigh,
                peerQ2, peerQ3, peerStores, bandQ1, band.map(PriceBand::q3).orElse(null),
                band.map(PriceBand::n).orElse(0L), demand, lastSale, today, commodityPct90, rpp, elasticity,
                itemStoreStats.txns(), benchmarkTargetMarginPct, guardrails, track, rampSalt);
        PricingMath.Recommendation rec = PricingMath.recommend(inputs, cfg).orElse(null);

        // The regional multiplier the chain would apply; "competition" only when the step ran
        // (a competitor anchor is already a local price, so the chain skips it).
        boolean localMarketOn = rpp != null && cfg.on(LOCAL_MARKET);
        double msaMultD = !localMarketOn ? 1
                : 1 - ((rpp.doubleValue() - 100) / 100) * (cfg.number(LOCAL_MARKET_WEIGHT) / 100);
        BigDecimal msaMult = BigDecimal.valueOf(msaMultD);
        boolean localMarketApplied = rec == null ? localMarketOn : stepApplied(rec, LOCAL_MARKET);
        String msaMode = localMarketApplied ? "competition" : "off";

        // The model decides the segment and which tier leads: a regular item leads with the
        // optimal price, an occasional one with the aggressive price (or the tenant's habit).
        String segment = rec != null ? rec.segment()
                : store != null && store.segment() != null ? store.segment() : "occasional";
        String recommendedTier = rec != null ? rec.headlineTier() : "optimal";

        return new PricingModel(item, productId, storeId, storeUuid, priceable,
                costR.map(Resolved::value).orElse(null), costR.map(Resolved::source).orElse(null),
                costR.map(Resolved::asOf).orElse(null),
                currentPriceValue, currentPriceR.map(Resolved::source).orElse(null),
                currentPriceR.map(Resolved::asOf).orElse(null),
                ownRef,
                // The anchor the chain actually started from, not the ladder's: with the internal
                // baseline on, that is the peer median and the count is the branches behind it.
                rec != null ? rec.anchor() : anchorR.map(Anchor::value).orElse(null),
                rec != null ? rec.anchorSource() : anchorR.map(Anchor::source).orElse(null),
                rec == null ? anchorR.map(Anchor::observations).orElse(0)
                        : "internal-peer".equals(rec.anchorSource()) || Anchor.PEER.equals(rec.anchorSource()) ? peerStores
                        : Anchor.COMPETITOR.equals(rec.anchorSource()) ? competitors.size()
                        : anchorR.map(Anchor::observations).orElse(0),
                rec != null ? rec.optimal() : null, rec != null ? rec.aggressive() : null, recommendedTier,
                rec != null ? rec.floor() : null, rec != null ? rec.ceiling() : null,
                peerQ1, peerQ2, peerQ3, peerStores,
                List.copyOf(competitors), competitorMedian,
                msaMult, msaMode, rpp,
                demandModel,
                segment,
                itemStoreStats.txns(), itemStoreStats.customers(),
                band.map(PriceBand::min).orElse(null), band.map(PriceBand::max).orElse(null),
                beta, elasticity.r2(), elasticity.basis(),

                commodityPct90, commodityLabel, commodityAsOf, commoditySource,
                w90Stats.units(), w90pStats.units(), itemStoreStats.units(), w30Stats.avgPrice(), w90pStats.avgPrice(),
                onHandUnits, inventoryAsOf, stale,
                buckets, rec, cfg,
                List.copyOf(locked));
    }

    /**
     * The pair's decision history, in the shape the chain reads: how many prices were already
     * applied for this item here (the phase-in's n, recorded deals plus price-list writes),
     * the lean the past decisions show, and the bulk strategy habit. A failing read - a
     * tenant whose decision tables are not there yet - degrades to no track record with a
     * warning, never a failed recommendation.
     */
    private Track track(String item, String storeCode, UUID productId, UUID storeUuid, LocalDate today,
            com.aatlas.history.PricingModel.Config cfg) {
        try {
            long priorApplied = deals.priorApplied(item, storeCode) + priceList.appliedCount(productId, storeUuid);
            LocalDate since = today.minusDays((long) cfg.number(LEARNING_WINDOW_DAYS));
            DecisionPatterns.Learning learning = null;
            if (cfg.on(LEARNING)) {
                List<Acceptance> rows = new ArrayList<>();
                if (storeCode != null) {
                    addAcceptance(rows, deals.acceptance(item, storeCode, since, LEARNING_ROWS_ITEM), Acceptance.ITEM_STORE);
                }
                addAcceptance(rows, deals.acceptance(item, null, since, LEARNING_ROWS_ITEM), Acceptance.ITEM);
                addAcceptance(rows, deals.acceptance(null, null, since, LEARNING_ROWS_TENANT), Acceptance.TENANT);
                learning = DecisionPatterns.learn(rows, today, cfg);
            }
            DecisionPatterns.Habit habit = null;
            if (cfg.on(LEARNING_STRATEGY)) {
                habit = DecisionPatterns.habit(deals.strategyPicks(since), HABIT_MIN_PICKS, HABIT_MIN_SHARE_PCT)
                        .orElse(null);
            }
            return new Track(priorApplied, learning, habit);
        } catch (RuntimeException ex) {
            log.warn("Decision history unavailable for {}@{} ({}); pricing without a track record", item,
                    storeCode == null ? "-" : storeCode, ex.getMessage());
            return Track.none();
        }
    }

    private static void addAcceptance(List<Acceptance> out, List<DealSummaries.Acceptance> rows, String basis) {
        for (DealSummaries.Acceptance a : rows) {
            out.add(new Acceptance(a.date(), a.suggested(), a.actual(), basis));
        }
    }

    private static boolean stepApplied(PricingMath.Recommendation rec, String key) {
        for (Step s : rec.steps()) {
            if (key.equals(s.key())) {
                return Step.APPLIED.equals(s.status());
            }
        }
        return false;
    }

    private static PricingModel emptyModel(String item, String storeId) {
        return new PricingModel(item, null, storeId, null, false,
                null, null, null, null, null, null, null,
                null, null, 0,
                null, null, "optimal", null, null,
                null, null, null, 0,
                List.of(), null,
                BigDecimal.ONE, "off", null,
                null,
                "occasional",
                0, 0,
                null, null,
                SalesHistory.Elasticity.defaultValue().coefficient(), null, SalesHistory.Elasticity.DEFAULT,
                null, null, null, null,
                null, null, null, null, null,
                null, null, false,
                List.of(), null, com.aatlas.history.PricingModel.Config.defaults(),
                List.of("margin", "demand", "inventory", "competitors", "forecast"));
    }

    private static DemandModel toDemandModel(PricingMath.Demand d) {
        return new DemandModel(d.level(), d.label(), BigDecimal.valueOf(d.index()), BigDecimal.valueOf(d.movePercent()),
                d.confidence(), BigDecimal.valueOf(d.confWeight()), d.recentVelocity(), d.expectedVelocity(),
                (int) d.maxAdjustmentPct(), d.trendDirection(), d.historyDays());
    }

    static BigDecimal marginPercent(BigDecimal price, BigDecimal cost) {
        return PricingMath.marginPct(price, cost);
    }

    /**
     * Port of {@code buildSellRecommendation} in {@code src/lib/platform/api.ts}: the "why
     * this price" derivation - both tiers, the benchmarks, the observed-price bands, the
     * calc steps and the factor weights, in one shape.
     */
    public SellDerivationDto getSellDerivation(String itemNumber, String storeId) {
        PricingModel m = getPricingModel(itemNumber, storeId);
        Optional<ProductRef> product = catalog.findProduct(itemNumber);
        Optional<StoreRef> store = (storeId == null || storeId.isBlank()) ? Optional.empty()
                : catalog.findStore(storeId);
        String description = product.map(ProductRef::description).orElse(itemNumber);

        PriceTierDto optimal = tierView("Optimal", m.optimalPrice(), m,
                "Best balance of margin and win rate. Sits inside the band this store already sells in.");
        PriceTierDto aggressive = tierView("Aggressive", m.aggressivePrice(), m,
                "Higher margin, higher risk of losing the line. Use where the customer is not shopping the price.");

        return new SellDerivationDto(itemNumber, description, storeId,
                Labels.dataStoreName(storeId, store.orElse(null)),
                m.priceable(), m.cost(), m.currentPrice(), marginPercent(m.currentPrice(), m.cost()),
                optimal, aggressive, m.recommendedTier(), demandDto(m.demand()), competitorDtos(m.competitors()),
                buildBenchmarks(m), buildBands(m), m.observedMin(), m.observedMax(),
                (int) m.totalTransactions(), buildCalcSteps(m, m.recommendedTier()), buildWeights(m, m.recommendedTier()),
                m.floorPrice(), m.peerQ1(), m.ceilingPrice(), modelSummary(m));
    }

    /** The model as it ran for the pair; null when the pair is not priceable. */
    static ModelSummaryDto modelSummary(PricingModel m) {
        PricingMath.Recommendation rec = m.recommendation();
        if (rec == null) {
            return null;
        }
        com.aatlas.history.PricingModel.Config cfg = m.modelConfig() != null ? m.modelConfig()
                : com.aatlas.history.PricingModel.Config.defaults();
        int[] toggles = cfg.toggleCount();
        return new ModelSummaryDto(toggles[0], toggles[1], rec.segment(), rec.headlineTier(),
                BigDecimal.valueOf(rec.maturity()).setScale(2, RoundingMode.HALF_UP), rec.externalRole(),
                rec.flags());
    }

    private static PriceTierDto tierView(String label, BigDecimal price, PricingModel m, String blurb) {
        if (price == null || m.currentPrice() == null) {
            return new PriceTierDto(label, price, marginPercent(price, m.cost()), null, null, null, blurb);
        }
        BigDecimal delta = PricingMath.round2(price.subtract(m.currentPrice()));
        BigDecimal deltaPct = PricingMath.pct(delta, m.currentPrice());
        return new PriceTierDto(label, price, marginPercent(price, m.cost()), delta, deltaPct, delta, blurb);
    }

    // -- pure derivations ------------------------------------------------------------------

    static BenchmarksDto buildBenchmarks(PricingModel m) {
        if (m.competitors().isEmpty()) {
            return new BenchmarksDto(null, null, null);
        }
        BigDecimal lowest = null;
        BigDecimal highest = null;
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (Competitor c : m.competitors()) {
            if (c.price() == null) {
                continue;
            }
            lowest = lowest == null || c.price().compareTo(lowest) < 0 ? c.price() : lowest;
            highest = highest == null || c.price().compareTo(highest) > 0 ? c.price() : highest;
            sum = sum.add(c.price());
            n++;
        }
        BigDecimal average = n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 2, java.math.RoundingMode.HALF_UP);
        return new BenchmarksDto(lowest, average, highest);
    }

    /** Real quartile buckets from {@code SalesHistory.PriceBand}: no jitter, no fabrication. */
    static List<PriceBandDto> buildBands(PricingModel m) {
        if (!m.priceable() || m.buckets().isEmpty()) {
            return List.of();
        }
        List<PriceBandDto> out = new ArrayList<>();
        for (Bucket b : m.buckets()) {
            String label = Fmt.fmtMoney(b.lo() == null ? 0 : b.lo().doubleValue()) + "-"
                    + Fmt.fmtMoney(b.hi() == null ? 0 : b.hi().doubleValue());
            out.add(new PriceBandDto(label, b.lo(), b.hi(), (int) b.n()));
        }
        return out;
    }

    static DemandInfoDto demandDto(DemandModel d) {
        if (d == null) {
            return null;
        }
        return new DemandInfoDto(d.level(), d.label(), d.index(), d.movePercent(), d.confidence(),
                d.confWeight(), d.recentVelocity(), d.expectedVelocity(), d.maxAdjustmentPct(),
                d.trendDirection(), d.historyDays());
    }

    static List<CompetitorDto> competitorDtos(List<Competitor> competitors) {
        return competitors.stream()
                .map(c -> new CompetitorDto(c.name(), c.domain(), c.price(), c.deltaVsCurrent()))
                .toList();
    }

    /**
     * The chain as it ran: the cost foundation, then one row per model step with both tiers
     * after it ("$10.26 / $11.36", or one figure while they still agree) and the step's status
     * as its {@code kind} - {@code applied}, {@code skipped} (on, but its input was missing) or
     * {@code off} (switched off in the model) - then the result, whose note names the headline.
     */
    static List<CalcStepDto> buildCalcSteps(PricingModel m, String tier) {
        List<CalcStepDto> steps = new ArrayList<>();
        if (m.cost() != null) {
            steps.add(new CalcStepDto("Cost foundation", Fmt.fmtMoney(m.cost().doubleValue()),
                    "Your landed cost for this item at this store (" + m.costSource() + ").", "step"));
        } else {
            steps.add(new CalcStepDto("Cost foundation", "No cost on file", "skipped: no cost on file", "step"));
        }

        PricingMath.Recommendation rec = m.recommendation();
        if (rec == null) {
            steps.add(new CalcStepDto("Recommended price", "Not priceable",
                    "No market anchor and no sales history for this pair.", "result"));
            return steps;
        }

        for (Step s : rec.steps()) {
            steps.add(new CalcStepDto(s.label(), tiers(s.win(), s.profit()), s.note(), s.status()));
        }

        BigDecimal price = "aggressive".equals(tier) ? rec.aggressive() : rec.optimal();
        String resultLabel = "aggressive".equals(tier) ? "Aggressive price" : "Optimal price";
        BigDecimal margin = marginPercent(price, m.cost());
        steps.add(new CalcStepDto(resultLabel, Fmt.fmtMoney(price.doubleValue()),
                headline(rec) + (margin == null ? " No cost on file, so the margin is unknown."
                        : " Keeps about " + Fmt.fixed(margin.doubleValue(), 1) + "% gross margin."),
                "result"));
        return steps;
    }

    /** "Optimal price leads (regular item)." */
    static String headline(PricingMath.Recommendation rec) {
        return capitalize(rec.headlineTier()) + " price leads (" + rec.segment() + " item).";
    }

    /** Both tiers after a step, or one figure while they agree. */
    private static String tiers(BigDecimal win, BigDecimal profit) {
        if (win == null && profit == null) {
            return "—";
        }
        if (win == null || profit == null || win.compareTo(profit) == 0) {
            return Fmt.fmtMoney((win != null ? win : profit).doubleValue());
        }
        return Fmt.fmtMoney(win.doubleValue()) + " / " + Fmt.fmtMoney(profit.doubleValue());
    }

    /** The adjusting steps a factor weight is derived from, by model key. */
    private static final Map<String, String> ADJUSTING_STEPS = Map.of(
            com.aatlas.history.PricingModel.DEMAND, "Demand (sales pace)",
            com.aatlas.history.PricingModel.COMMODITY, "Commodity pass-through",
            LOCAL_MARKET, "Local market (RPP)",
            LEARNING, "Your decisions");

    /**
     * {@code buildWeights}: a share of the move each applied step contributed to the headline
     * tier - the anchor by how much it is trusted, the cost floor, then demand, commodity, the
     * local market and the tenant's own decisions by how far each actually moved the price -
     * scaled to sum to 100.
     */
    static List<FactorWeightDto> buildWeights(PricingModel m, String tier) {
        record Raw(String label, double w, String direction) {
        }
        PricingMath.Recommendation rec = m.recommendation();
        if (rec == null) {
            return List.of();
        }
        List<Raw> raw = new ArrayList<>();
        String source = rec.anchorSource() == null ? "" : rec.anchorSource();
        String anchorLabel = switch (source) {
            case "internal-peer" -> "Your other branches";
            case Anchor.COMPETITOR -> "Competitor benchmark";
            case Anchor.PEER -> "Peer benchmark";
            case Anchor.BENCHMARK -> "Benchmark margin";
            case Anchor.HISTORY -> "Own price history";
            default -> "Own reference price";
        };
        double anchorW = switch (source) {
            case "internal-peer" -> 0.5;
            case Anchor.COMPETITOR -> PricingMath.WEIGHT_COMPETITOR;
            case Anchor.PEER -> PricingMath.WEIGHT_PEER;
            case Anchor.BENCHMARK -> PricingMath.WEIGHT_BENCHMARK;
            default -> 0.15;
        };
        BigDecimal anchorRef = rec.anchor() != null ? rec.anchor() : rec.ownRef();
        raw.add(new Raw(anchorLabel, Math.max(0.05, anchorW) * 100,
                m.currentPrice() != null && anchorRef != null && anchorRef.compareTo(m.currentPrice()) > 0 ? "up" : "down"));
        if (m.cost() != null) {
            raw.add(new Raw("Cost + margin floor", 22, "up"));
        }
        boolean aggressive = "aggressive".equals(tier);
        BigDecimal before = null;
        for (Step s : rec.steps()) {
            BigDecimal after = aggressive ? s.profit() : s.win();
            String label = ADJUSTING_STEPS.get(s.key());
            if (label != null && Step.APPLIED.equals(s.status()) && before != null && after != null
                    && before.signum() != 0) {
                double movePct = after.subtract(before).doubleValue() / before.doubleValue() * 100;
                if (Math.abs(movePct) >= 0.05) {
                    raw.add(new Raw(label, 10 + Math.min(10, Math.abs(movePct)), movePct > 0 ? "up" : "down"));
                }
            }
            if (after != null) {
                before = after;
            }
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        double total = raw.stream().mapToDouble(Raw::w).sum();
        List<FactorWeightDto> scaled = new ArrayList<>();
        int sumSoFar = 0;
        for (Raw r : raw) {
            int pct = (int) Math.round((r.w() / total) * 100);
            sumSoFar += pct;
            scaled.add(new FactorWeightDto(r.label(), bd(pct), r.direction()));
        }
        int drift = 100 - sumSoFar;
        FactorWeightDto first = scaled.get(0);
        scaled.set(0, new FactorWeightDto(first.label(), bd(first.percent().intValue() + drift), first.direction()));
        return scaled;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
