package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.clamp;
import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.seed.Seeded;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.StoreRef;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/mock/pricing.ts}: the pure function of (item, store) every other
 * sell engine builds on. See that file's own doc comment for why the split between "the
 * number" (here) and "the decision" ({@code sell.ts}, {@link SellEngine}) exists.
 */
@Component
public class PricingEngine {

    /** {@code COMPETITOR_DOMAINS} in {@code src/lib/mock/catalog.ts}, in order. */
    private static final List<Domain> COMPETITOR_DOMAINS = List.of(
            new Domain("Northline Supply", "northline.example.com"),
            new Domain("Castellan Trade", "castellan.example.com"),
            new Domain("Redhawk Building Supply", "redhawk.example.com"),
            new Domain("Vantage Wholesale", "vantage.example.com"),
            new Domain("Ironbridge Industrial", "ironbridge.example.com"),
            new Domain("Cobalt Depot", "cobalt.example.com"),
            new Domain("Fairhaven Supply", "fairhaven.example.com"),
            new Domain("Pemberton Trade Group", "pemberton.example.com"),
            new Domain("Granite & Co.", "granite.example.com"),
            new Domain("Larkspur Distribution", "larkspur.example.com"));

    private record Domain(String name, String domain) {
    }

    private final CatalogGateway catalog;

    public PricingEngine(CatalogGateway catalog) {
        this.catalog = catalog;
    }

    // -- s1: base cost --------------------------------------------------------------------

    static double baseCost(String item) {
        double r = Seeded.rand(item, "cost");
        long tier = Seeded.hashString(item) % 10;
        if (tier <= 4) {
            return round2(1.2 + r * 12);
        }
        if (tier <= 8) {
            return round2(18 + r * 120);
        }
        return round2(320 + r * 900);
    }

    // -- demand -----------------------------------------------------------------------------

    static DemandModel buildDemand(String item, String storeId) {
        String key = item + "|" + storeId;
        boolean applied = Seeded.rand(key, "demand-applied") > 0.25;
        if (!applied) {
            return null;
        }
        double roll = Seeded.rand(key, "demand-level");
        String level = roll > 0.62 ? "high" : roll > 0.3 ? "medium" : "low";
        int maxAdjustmentPct = 3;
        double confWeight = round2(Seeded.randRange(key, "demand-conf", 0.35, 0.95));
        int direction = level.equals("high") ? 1 : level.equals("low") ? -1 : 0;
        double movePercent = Math.round(direction * maxAdjustmentPct * confWeight * 10) / 10.0;
        double expectedVelocity = round2(Seeded.randRange(key, "demand-exp", 0.4, 9));
        double ratio = switch (level) {
            case "high" -> Seeded.randRange(key, "demand-ratio", 1.15, 1.9);
            case "low" -> Seeded.randRange(key, "demand-ratio", 0.45, 0.85);
            default -> Seeded.randRange(key, "demand-ratio", 0.9, 1.1);
        };
        String label = level.equals("high") ? "High demand" : level.equals("low") ? "Low demand" : "Stable demand";
        String confidence = confWeight > 0.75 ? "high" : confWeight > 0.5 ? "medium" : "low";
        String trendDirection = level.equals("high") ? "INCREASING" : level.equals("low") ? "DECREASING" : "STABLE";
        int historyDays = (int) Math.round(Seeded.randRange(key, "demand-hist", 90, 540));
        double index = Math.round(ratio * 1000) / 1000.0;
        double recentVelocity = round2(expectedVelocity * ratio);
        return new DemandModel(level, label, index, movePercent, confidence, confWeight, recentVelocity,
                expectedVelocity, maxAdjustmentPct, trendDirection, historyDays);
    }

    // -- competitors --------------------------------------------------------------------------

    static List<Competitor> buildCompetitors(String item, String storeId, double currentPrice) {
        String key = item + "|" + storeId;
        if (Seeded.rand(key, "comp-none") > 0.86) {
            return List.of();
        }
        int count = (int) (4 + Math.floor(Seeded.rand(key, "comp-count") * 6));
        List<Competitor> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, COMPETITOR_DOMAINS.size()); i++) {
            Domain c = COMPETITOR_DOMAINS.get(i);
            double spread = Seeded.randRange(key, "comp-" + c.domain(), 0.82, 1.24);
            double price = round2(currentPrice * spread);
            out.add(new Competitor(c.name(), c.domain(), price, round2(price - currentPrice)));
        }
        return List.copyOf(out);
    }

    // -- the resolver -------------------------------------------------------------------------

    public PricingModel getPricingModel(String item, String storeId) {
        String key = item + "|" + storeId;
        boolean hasStoreId = storeId != null && !storeId.isBlank();
        ProductRef product = catalog.findProduct(item).orElse(null);
        StoreRef store = hasStoreId ? catalog.findStore(storeId).orElse(null) : null;
        boolean hasSales = product != null && product.hasSales();
        boolean sellsHere = hasStoreId && hasSales && catalog.sells(item, storeId);
        boolean priceable = product != null && hasSales && (!hasStoreId || sellsHere);

        double cost = baseCost(item);
        double currentPrice = round2(cost * Seeded.randRange(key, "current", 1.32, 2.15));
        double peerQ2 = round2(cost * Seeded.randRange(item, "peer", 1.45, 2.1));
        double peerQ1 = round2(peerQ2 * 0.88);
        double peerQ3 = round2(peerQ2 * 1.19);
        List<Competitor> competitors = buildCompetitors(item, storeId, currentPrice);

        double[] compPrices = competitors.stream().mapToDouble(Competitor::price).sorted().toArray();
        OptionalDouble competitorMedian = compPrices.length == 0 ? OptionalDouble.empty()
                : OptionalDouble.of(round2(compPrices[compPrices.length / 2]));

        double hardFloor = round2(cost / 0.75);
        double historyFloor = round2(Math.max(hardFloor, peerQ1 * 0.97));
        double ceilingAnchor = competitorMedian.orElse(peerQ3);
        double ceilingPrice = round2(Math.max(peerQ3, ceilingAnchor * 1.18));

        OptionalDouble rpp = (store != null && store.rpp() != null)
                ? OptionalDouble.of(store.rpp().doubleValue()) : OptionalDouble.empty();
        String msaMode = Seeded.rand(key, "msa-mode") > 0.94 ? "off" : "competition";
        double mult = (rpp.isEmpty() || msaMode.equals("off")) ? 1 : 1 - ((rpp.getAsDouble() - 100) / 100) * 0.55;

        double anchor = competitorMedian.orElse(peerQ1);
        double optimal = anchor * Seeded.randRange(key, "opt", 0.95, 1.04) * mult;
        DemandModel demand = buildDemand(item, storeId);
        if (demand != null) {
            optimal = optimal * (1 + demand.movePercent() / 100);
        }
        double optimalPrice = round2(clamp(optimal, hardFloor, ceilingPrice));
        double aggressivePrice = round2(Math.min(
                Math.max(optimalPrice * Seeded.randRange(key, "agg", 1.06, 1.19), optimalPrice + 0.05),
                ceilingPrice * 1.02));

        String segment = (store != null && store.segment() != null) ? store.segment() : "occasional";
        String recommendedTier = segment.equals("regular") ? "aggressive" : "optimal";
        int totalTransactions = (int) Math.round(Seeded.randRange(key, "txns", 8, 240));
        int totalCompanies = (int) Math.round(Seeded.randRange(item, "companies", 12, 380));
        double observedMin = round2(Math.min(currentPrice, optimalPrice) * Seeded.randRange(key, "omin", 0.72, 0.93));
        double observedMax = round2(Math.max(currentPrice, aggressivePrice) * Seeded.randRange(key, "omax", 1.06, 1.34));
        double msaMult = Math.round(mult * 10000) / 10000.0;

        return new PricingModel(item, storeId, priceable, cost, currentPrice, optimalPrice, aggressivePrice,
                recommendedTier, historyFloor, ceilingPrice, peerQ1, peerQ2, peerQ3, competitors, competitorMedian,
                msaMult, msaMode, demand, segment, totalTransactions, totalCompanies, observedMin, observedMax);
    }

    /**
     * Port of {@code buildSellRecommendation} in {@code src/lib/platform/api.ts}: the "why
     * this price" derivation - both tiers, the benchmarks, the observed-price bands, the
     * calc steps and the factor weights, in one shape.
     */
    public SellDerivationDto getSellDerivation(String itemNumber, String storeId) {
        PricingModel m = getPricingModel(itemNumber, storeId);
        ProductRef product = catalog.findProduct(itemNumber).orElse(null);
        StoreRef store = catalog.findStore(storeId).orElse(null);
        String description = product != null ? product.description() : itemNumber;

        PriceTierDto optimal = tierView("Optimal", m.optimalPrice(), m,
                "Best balance of margin and win rate. Sits inside the band this store already sells in.");
        PriceTierDto aggressive = tierView("Aggressive", m.aggressivePrice(), m,
                "Higher margin, higher risk of losing the line. Use where the customer is not shopping the price.");

        return new SellDerivationDto(itemNumber, description, storeId, Labels.dataStoreName(storeId, store),
                m.priceable(), bd(m.cost()), bd(m.currentPrice()), bd(marginPercent(m.currentPrice(), m.cost())),
                optimal, aggressive, m.recommendedTier(), demandDto(m.demand()), competitorDtos(m.competitors()),
                buildBenchmarks(m), buildBands(m, "12M"), bd(m.observedMin()), bd(m.observedMax()),
                m.totalTransactions(), buildCalcSteps(m, m.recommendedTier()), buildWeights(m, m.recommendedTier()),
                bd(round2(m.cost() / 0.75)), bd(m.floorPrice()), bd(m.ceilingPrice()));
    }

    private static PriceTierDto tierView(String label, double price, PricingModel m, String blurb) {
        double delta = round2(price - m.currentPrice());
        double deltaPct = round2((delta / m.currentPrice()) * 100);
        return new PriceTierDto(label, bd(price), bd(marginPercent(price, m.cost())), bd(delta), bd(deltaPct),
                bd(delta), blurb);
    }

    // -- pure derivations ------------------------------------------------------------------

    static double marginPercent(double price, double cost) {
        if (price == 0) {
            return 0;
        }
        return round2(((price - cost) / price) * 100);
    }

    static double markupPercent(double price, double cost) {
        if (cost == 0) {
            return 0;
        }
        return round2(((price - cost) / cost) * 100);
    }

    static double periodScale(String period) {
        return switch (period) {
            case "3M" -> 0.28;
            case "6M" -> 0.55;
            case "12M" -> 1.0;
            default -> 1.35;
        };
    }

    static int transactionsForPeriod(PricingModel m, String period) {
        return (int) Math.max(1, Math.round(m.totalTransactions() * periodScale(period)));
    }

    static BenchmarksDto buildBenchmarks(PricingModel m) {
        if (m.competitors().isEmpty()) {
            return new BenchmarksDto(null, null, null);
        }
        double[] prices = m.competitors().stream().mapToDouble(Competitor::price).toArray();
        double lowest = round2(Arrays.stream(prices).min().orElse(0));
        double highest = round2(Arrays.stream(prices).max().orElse(0));
        double average = round2(Arrays.stream(prices).sum() / prices.length);
        return new BenchmarksDto(bd(lowest), bd(average), bd(highest));
    }

    static List<PriceBandDto> buildBands(PricingModel m, String period) {
        if (!m.priceable()) {
            return List.of();
        }
        double lo = m.observedMin();
        double hi = m.observedMax();
        double width = (hi - lo) / 4;
        if (width <= 0) {
            return List.of();
        }
        double scale = periodScale(period);
        double[] shape = {0.18, 0.34, 0.31, 0.17};
        List<PriceBandDto> out = new ArrayList<>();
        String key = m.item() + "|" + m.storeId();
        for (int i = 0; i < 4; i++) {
            double min = round2(lo + width * i);
            double max = round2(i == 3 ? hi : lo + width * (i + 1) - 0.01);
            double jitter = Seeded.randRange(key, "band-" + period + "-" + i, 0.7, 1.3);
            int count = (int) Math.max(1, Math.round(m.totalTransactions() * scale * shape[i] * jitter));
            String label = Fmt.fmtMoney(min) + "-" + Fmt.fmtMoney(max);
            out.add(new PriceBandDto(label, bd(min), bd(max), count));
        }
        return out;
    }

    static DemandInfoDto demandDto(DemandModel d) {
        if (d == null) {
            return null;
        }
        return new DemandInfoDto(d.level(), d.label(), bd(d.index()), bd(d.movePercent()), d.confidence(),
                bd(d.confWeight()), bd(d.recentVelocity()), bd(d.expectedVelocity()), d.maxAdjustmentPct(),
                d.trendDirection(), d.historyDays());
    }

    static List<CompetitorDto> competitorDtos(List<Competitor> competitors) {
        return competitors.stream()
                .map(c -> new CompetitorDto(c.name(), c.domain(), bd(c.price()), bd(c.deltaVsCurrent())))
                .toList();
    }

    /** {@code buildCalcSteps}: cost -> competitor benchmark -> peer band -> local market -> demand -> guardrails -> result. */
    static List<CalcStepDto> buildCalcSteps(PricingModel m, String tier) {
        double price = tier.equals("aggressive") ? m.aggressivePrice() : m.optimalPrice();
        double anchor = m.competitorMedian().orElse(m.peerQ1());
        boolean hasCompetitorMedian = m.competitorMedian().isPresent();
        List<CalcStepDto> steps = new ArrayList<>();

        steps.add(new CalcStepDto("Cost foundation", Fmt.fmtMoney(m.cost()),
                "Your landed cost for this item at this store.", "step"));

        steps.add(new CalcStepDto(
                hasCompetitorMedian ? "Competitor benchmark" : "Peer store benchmark",
                Fmt.fmtMoney(anchor),
                hasCompetitorMedian
                        ? "Median of " + m.competitors().size() + " scraped competitor prices."
                        : "No trusted competitor prices here, so the lowest typical price other stores in the "
                                + "network charge was used instead.",
                "step"));

        steps.add(new CalcStepDto("Peer pricing band",
                Fmt.fmtMoney(m.peerQ1()) + " – " + Fmt.fmtMoney(m.peerQ3()),
                "Q1 to Q3 of what other stores in the network charge for this item.", "step"));

        boolean rppOff = m.msaMode().equals("off");
        String localValue = rppOff ? "not applied" : Fmt.fixed((m.msaMult() - 1) * 100, 1) + "%";
        String localNote = rppOff
                ? "Local-market pricing is switched off for this store, so no location adjustment was made."
                : m.msaMult() < 1
                        ? "A busier market with more supply houses nearby, so the calculated price was eased "
                                + "down to win the sale."
                        : "Fewer competitors nearby than average, so the calculated price was lifted a little.";
        steps.add(new CalcStepDto("Local market (RPP)", localValue, localNote, "step"));

        if (m.demand() != null) {
            DemandModel d = m.demand();
            String value = (d.movePercent() > 0 ? "+" : "") + Fmt.fixed(d.movePercent(), 1) + "%";
            String note = d.label() + " — recent pace " + Fmt.jsNum(d.recentVelocity()) + "/day against an "
                    + "expected " + Fmt.jsNum(d.expectedVelocity()) + "/day baseline, scaled by "
                    + Math.round(d.confWeight() * 100) + "% confidence and bounded to ±"
                    + d.maxAdjustmentPct() + "%.";
            steps.add(new CalcStepDto("Demand (recent sales pace)", value, note, "demand"));
        }

        steps.add(new CalcStepDto("Guardrails",
                "floor " + Fmt.fmtMoney(round2(m.cost() / 0.75)) + " · ceiling " + Fmt.fmtMoney(m.ceilingPrice()),
                "A 25% margin floor and a market ceiling bound every recommendation. Neither the local-market "
                        + "shift nor the demand move can push a price outside them.",
                "step"));

        String resultLabel = tier.equals("aggressive") ? "Aggressive price" : "Optimal price";
        List<String> lines = new ArrayList<>();
        lines.add("Start from the " + (hasCompetitorMedian ? "competitor median" : "peer anchor") + " of "
                + Fmt.fmtMoney(anchor) + ".");
        lines.add(rppOff
                ? "No local-market adjustment (switched off for this store)."
                : "Apply the local-market multiplier " + Fmt.fixed(m.msaMult(), 4) + ".");
        lines.add(m.demand() != null
                ? "Apply the demand move of " + (m.demand().movePercent() > 0 ? "+" : "")
                        + Fmt.fixed(m.demand().movePercent(), 1) + "%."
                : "No actionable demand signal for this item at this store, so no demand move.");
        lines.add(tier.equals("aggressive")
                ? "Take the top of what this store’s history and the market support."
                : "Take the price that wins the sale while keeping a healthy margin.");
        lines.add("Clamp to the guardrails, giving " + Fmt.fmtMoney(price) + ".");
        lines.add("That keeps about " + Fmt.fixed(marginPercent(price, m.cost()), 1)
                + "% gross margin ((price − cost) ÷ price).");
        steps.add(new CalcStepDto(resultLabel, Fmt.fmtMoney(price), String.join("\n", lines), "result"));
        return steps;
    }

    /** {@code buildWeights}: renormalised so the bars sum to exactly 100. */
    static List<FactorWeightDto> buildWeights(PricingModel m, String tier) {
        String key = m.item() + "|" + m.storeId() + "|" + tier;
        record Raw(String label, double w, String direction) {
        }
        List<Raw> raw = new ArrayList<>();
        double anchor = m.competitorMedian().orElse(m.peerQ1());
        raw.add(new Raw(
                m.competitorMedian().isPresent() ? "Competitor benchmark" : "Peer store prices",
                Seeded.randRange(key, "w1", 30, 48),
                anchor > m.currentPrice() ? "up" : "down"));
        raw.add(new Raw("Cost + margin floor", Seeded.randRange(key, "w2", 16, 28), "up"));
        raw.add(new Raw("Customer segment", Seeded.randRange(key, "w3", 8, 20),
                m.segment().equals("regular") ? "up" : "down"));
        if (m.msaMode().equals("competition") && Math.abs(m.msaMult() - 1) > 0.005) {
            raw.add(new Raw("Local market (RPP)", Seeded.randRange(key, "w4", 6, 16),
                    m.msaMult() > 1 ? "up" : "down"));
        }
        if (m.demand() != null && m.demand().movePercent() != 0) {
            raw.add(new Raw("Demand (sales pace)", Seeded.randRange(key, "w5", 4, 14),
                    m.demand().movePercent() > 0 ? "up" : "down"));
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
        if (!scaled.isEmpty()) {
            FactorWeightDto first = scaled.get(0);
            scaled.set(0, new FactorWeightDto(first.label(), bd(first.percent().intValue() + drift), first.direction()));
        }
        return scaled;
    }
}
