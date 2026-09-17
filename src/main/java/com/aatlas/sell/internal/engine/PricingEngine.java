package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Anchor;
import com.aatlas.history.Catalogue.ProductRef;
import com.aatlas.history.Catalogue.StoreRef;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.CompetitorPrices.Observation;
import com.aatlas.history.Inventory;
import com.aatlas.history.PriceLadder;
import com.aatlas.history.PricingMath;
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
import com.aatlas.sell.internal.dto.PricingDtos.PriceBandDto;
import com.aatlas.sell.internal.dto.PricingDtos.PriceTierDto;
import com.aatlas.sell.internal.dto.PricingDtos.SellDerivationDto;
import com.aatlas.sell.internal.engine.PricingTypes.Competitor;
import com.aatlas.sell.internal.engine.PricingTypes.DemandModel;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    private final CatalogGateway catalog;
    private final SalesHistory sales;
    private final PriceLadder ladder;
    private final Inventory inventory;
    private final CompetitorPrices competitorPrices;
    private final Reference reference;
    private final AatlasClock clock;

    public PricingEngine(CatalogGateway catalog, SalesHistory sales, PriceLadder ladder, Inventory inventory,
            CompetitorPrices competitorPrices, Reference reference, AatlasClock clock) {
        this.catalog = catalog;
        this.sales = sales;
        this.ladder = ladder;
        this.inventory = inventory;
        this.competitorPrices = competitorPrices;
        this.reference = reference;
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

        BigDecimal rpp = store != null ? store.rpp() : null;
        double msaMultD = rpp != null ? 1 - ((rpp.doubleValue() - 100) / 100) * 0.55 : 1;
        BigDecimal msaMult = BigDecimal.valueOf(msaMultD);
        String msaMode = rpp != null ? "competition" : "off";

        SalesHistory.Elasticity elasticity = sales.elasticity(productId, storeUuid, today);
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

        Optional<PricingMath.Recommendation> recOpt = PricingMath.recommend(costR.map(Resolved::value).orElse(null),
                anchorR.orElse(null), ownRef, demand, commodityPct90, msaMultD, beta.doubleValue(), guardrails,
                bandQ1, peerQ3);
        PricingMath.Recommendation rec = recOpt.orElse(null);

        String segment = store != null && store.segment() != null ? store.segment() : "occasional";
        String recommendedTier = "regular".equals(segment) ? "aggressive" : "optimal";

        return new PricingModel(item, productId, storeId, storeUuid, priceable,
                costR.map(Resolved::value).orElse(null), costR.map(Resolved::source).orElse(null),
                costR.map(Resolved::asOf).orElse(null),
                currentPriceValue, currentPriceR.map(Resolved::source).orElse(null),
                currentPriceR.map(Resolved::asOf).orElse(null),
                ownRef,
                anchorR.map(Anchor::value).orElse(null), anchorR.map(Anchor::source).orElse(null),
                anchorR.map(Anchor::observations).orElse(0),
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

                commodityPct90, commodityLabel, commodityAsOf,
                w90Stats.units(), w90pStats.units(), itemStoreStats.units(), w30Stats.avgPrice(), w90pStats.avgPrice(),
                onHandUnits, inventoryAsOf, stale,
                buckets, rec,
                List.copyOf(locked));
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
                null, null, null,
                null, null, null, null, null,
                null, null, false,
                List.of(), null,
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
                m.floorPrice(), m.peerQ1(), m.ceilingPrice());
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

    /** The real recommendation chain, {@code PricingMath.recommend}'s own running values. */
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

        boolean hasAnchor = m.anchorValue() != null;
        steps.add(new CalcStepDto(hasAnchor ? m.anchorSource() + " benchmark" : "Own reference price",
                hasAnchor ? Fmt.fmtMoney(m.anchorValue().doubleValue()) : "—",
                hasAnchor ? m.anchorObservations() + " observation(s) behind the " + m.anchorSource() + " anchor."
                        : "No market anchor; the pair's own last price is the starting point.",
                "step"));

        if (m.peerQ1() != null && m.peerQ3() != null) {
            steps.add(new CalcStepDto("Peer pricing band",
                    Fmt.fmtMoney(m.peerQ1().doubleValue()) + " – " + Fmt.fmtMoney(m.peerQ3().doubleValue()),
                    "Q1 to Q3 of what other stores in the network charge for this item.", "step"));
        }

        if (m.demand() != null) {
            DemandModel d = m.demand();
            steps.add(new CalcStepDto("Demand (recent sales pace)",
                    (d.movePercent().signum() > 0 ? "+" : "") + Fmt.fixed(d.movePercent().doubleValue(), 1) + "%",
                    d.label() + " — recent pace " + Fmt.jsNum(d.recentVelocity().doubleValue()) + "/wk against "
                            + Fmt.jsNum(d.expectedVelocity().doubleValue()) + "/wk expected.",
                    "demand"));
        }

        if (m.commodityPct90() != null) {
            steps.add(new CalcStepDto("Commodity pass-through",
                    Fmt.fixed(m.commodityPct90().doubleValue(), 1) + "%",
                    (m.commodityLabel() != null ? m.commodityLabel() : "Commodity index") + " (reference, as of "
                            + m.commodityAsOf() + "); a quarter of the 90-day move reaches the price.",
                    "step"));
        }

        boolean rppOff = "off".equals(m.msaMode());
        steps.add(new CalcStepDto("Local market (RPP)",
                rppOff ? "not applied" : Fmt.fixed((m.msaMult().doubleValue() - 1) * 100, 1) + "%",
                rppOff ? "This branch is priced nationally." : "Regional price parity adjustment.", "step"));

        String floorLabel = m.cost() != null ? Fmt.fmtMoney(rec.floor().doubleValue())
                : "own lower quartile " + Fmt.fmtMoney(rec.floor() == null ? 0 : rec.floor().doubleValue());
        steps.add(new CalcStepDto("Guardrails",
                "floor " + floorLabel + " · ceiling " + Fmt.fmtMoney(rec.ceiling().doubleValue()),
                "A minimum-margin floor and a market ceiling bound every recommendation.", "step"));

        BigDecimal price = "aggressive".equals(tier) ? rec.aggressive() : rec.optimal();
        String resultLabel = "aggressive".equals(tier) ? "Aggressive price" : "Optimal price";
        steps.add(new CalcStepDto(resultLabel, Fmt.fmtMoney(price.doubleValue()),
                "Clamped to the guardrails; keeps about "
                        + Fmt.fixed(marginPercent(price, m.cost()) == null ? 0 : marginPercent(price, m.cost()).doubleValue(), 1)
                        + "% gross margin.",
                "result"));
        return steps;
    }

    /** {@code buildWeights}: a share of the move each real factor contributed, summing to 100. */
    static List<FactorWeightDto> buildWeights(PricingModel m, String tier) {
        record Raw(String label, double w, String direction) {
        }
        List<Raw> raw = new ArrayList<>();
        boolean hasAnchor = m.anchorValue() != null;
        if (hasAnchor) {
            double w = switch (m.anchorSource() == null ? "" : m.anchorSource()) {
                case Anchor.COMPETITOR -> PricingMath.WEIGHT_COMPETITOR;
                case Anchor.PEER -> PricingMath.WEIGHT_PEER;
                case Anchor.BENCHMARK -> PricingMath.WEIGHT_BENCHMARK;
                default -> 0.15;
            };
            raw.add(new Raw(m.anchorSource() != null ? capitalize(m.anchorSource()) + " benchmark" : "Market",
                    Math.max(0.05, w) * 100,
                    m.currentPrice() != null && m.anchorValue().compareTo(m.currentPrice()) > 0 ? "up" : "down"));
        }
        raw.add(new Raw("Cost + margin floor", 22, "up"));
        if (m.demand() != null && m.demand().movePercent().signum() != 0) {
            raw.add(new Raw("Demand (sales pace)", 10 + Math.min(10, Math.abs(m.demand().movePercent().doubleValue())),
                    m.demand().movePercent().signum() > 0 ? "up" : "down"));
        }
        if (!"off".equals(m.msaMode()) && m.msaMult() != null && Math.abs(m.msaMult().doubleValue() - 1) > 0.005) {
            raw.add(new Raw("Local market (RPP)", 10, m.msaMult().doubleValue() > 1 ? "up" : "down"));
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
