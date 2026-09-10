package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.PricingEngine.marginPercent;
import static com.aatlas.sell.internal.engine.Round.clamp;
import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.seed.Seeded;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.RegionRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.StoreRef;
import com.aatlas.sell.internal.dto.SellDtos.ChainStepDto;
import com.aatlas.sell.internal.dto.SellDtos.ForecastSummaryDto;
import com.aatlas.sell.internal.dto.SellDtos.NowSummaryDto;
import com.aatlas.sell.internal.dto.SellDtos.NowVsWaitDto;
import com.aatlas.sell.internal.dto.SellDtos.ScenarioResultDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import com.aatlas.sell.internal.dto.SellDtos.TimelinePointDto;
import com.aatlas.sell.internal.dto.SellDtos.WaitSummaryDto;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/intel/sell.ts}: {@code getSellIntel} and {@code runScenario}. The
 * pricing engine ({@link PricingEngine}) still decides the number; this turns it into a
 * decision - the chain, the 90-day timeline, sell-now-or-wait, and volume/inventory.
 */
@Component
public class SellEngine {

    private static final String[] MONTHS =
            {"Oct", "Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep"};

    private final CatalogGateway catalog;
    private final PricingEngine pricing;
    private final ElasticityEngine elasticity;

    public SellEngine(CatalogGateway catalog, PricingEngine pricing, ElasticityEngine elasticity) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.elasticity = elasticity;
    }

    public static String confidenceLabel(int n) {
        return n >= 85 ? "High" : n >= 70 ? "Medium" : "Low";
    }

    /** Units this store moves of this item in a month. Cheap fittings move by the thousand. */
    public static int monthlyUnitsFor(String itemNumber, String storeId, double cost) {
        String key = "vol:" + itemNumber + ":" + storeId;
        if (cost < 15) {
            return Seeded.randInt(key, "u", 700, 4200);
        }
        if (cost < 200) {
            return Seeded.randInt(key, "u", 90, 900);
        }
        return Seeded.randInt(key, "u", 4, 38);
    }

    record Inventory(int units, double weeksOfCover) {
    }

    public static Inventory inventoryFor(String itemNumber, String storeId, int monthlyUnits) {
        String key = "inv:" + itemNumber + ":" + storeId;
        double weeksOfCover = round1(Seeded.randRange(key, "cover", 2.2, 24));
        int units = (int) Math.max(1, Math.round((monthlyUnits / 4.33) * weeksOfCover));
        return new Inventory(units, weeksOfCover);
    }

    record Drift(double pct, String driver) {
    }

    Drift priceDriftPct90(PricingModel m, String commodityKey) {
        CommodityTrend commodity = catalog.commodityTrend(commodityKey);
        double demand = m.demand() != null ? m.demand().movePercent() * 1.6 : 0;
        double noise = round1(Seeded.randRange(m.item() + "|" + m.storeId(), "fc-noise", -1.4, 1.4));
        double pct = round1(commodity.pct90() * 0.72 + demand + noise);
        String driver = Math.abs(commodity.pct90()) >= Math.abs(demand)
                ? commodity.label()
                : m.demand() != null ? m.demand().label() + " at this store" : "Local demand";
        return new Drift(pct, driver);
    }

    private RegionRef resolveRegion(StoreRef store) {
        String regionKey = store != null && store.regionKey() != null ? store.regionKey() : "north";
        return catalog.regionOf(regionKey)
                .or(() -> catalog.allRegions().stream().findFirst())
                .orElse(new RegionRef(regionKey, regionKey, regionKey));
    }

    public SellIntelDto getSellIntel(String itemNumber, String storeId) {
        PricingModel m = pricing.getPricingModel(itemNumber, storeId);
        ProductRef product = catalog.findProduct(itemNumber).orElse(null);
        StoreRef store = catalog.findStore(storeId).orElse(null);
        RegionRef region = resolveRegion(store);
        String key = itemNumber + "|" + storeId;

        String name = product != null ? product.shortName() : itemNumber;
        String category = product != null ? product.category() : "Plumbing";
        String commodityKey = product != null ? product.commodity() : "none";
        String storeLabel = Labels.storeLabel(storeId, store);

        if (!m.priceable()) {
            ChainStepDto emptyFinal = new ChainStepDto("final", "Recommended price", "—", null, "", bd(0));
            return new SellIntelDto(itemNumber, storeId, false, name, itemNumber, storeLabel, region.compassLabel(),
                    category, bd(m.cost()), bd(0), bd(0), bd(0), bd(0), bd(0), bd(0), bd(0), bd(0), bd(0), bd(0),
                    0, "Low", 0, null, null, bd(0), "No signal", bd(0), List.of(), emptyFinal, List.of(),
                    new ForecastSummaryDto(bd(0), bd(0), bd(0), bd(0), ""),
                    new NowVsWaitDto("now", "", new NowSummaryDto(bd(0), bd(0), bd(0)),
                            new WaitSummaryDto(14, bd(0), bd(0), bd(0), "High")),
                    bd(0), 0, 0, 0, bd(0), bd(0), bd(0));
        }

        double anchor = m.competitorMedian().orElse(m.peerQ1());
        double optFactor = Seeded.randRange(key, "opt", 0.95, 1.04);
        double afterHistory = anchor * optFactor;
        double afterRegion = afterHistory * m.msaMult();
        double demandPct = m.demand() != null ? m.demand().movePercent() : 0;
        double afterDemand = afterRegion * (1 + demandPct / 100);
        double recommended = m.optimalPrice();
        double marginFloor = round2(m.cost() / 0.75);

        double[] compPrices = m.competitors().stream().mapToDouble(PricingTypes.Competitor::price).toArray();
        boolean hasCompetitors = compPrices.length > 0;
        double competitorLow = hasCompetitors ? round2(java.util.Arrays.stream(compPrices).min().orElse(0)) : 0;
        double competitorHigh = hasCompetitors ? round2(java.util.Arrays.stream(compPrices).max().orElse(0)) : 0;
        double observedMedian = round2((m.observedMin() + m.observedMax()) / 2);

        List<ChainStepDto> chain = new ArrayList<>();
        chain.add(new ChainStepDto("market", "Market benchmark", Fmt.fmtMoney(anchor), null,
                m.competitorMedian().isPresent()
                        ? "Median of " + m.competitors().size() + " competitor prices. The starting point."
                        : "No competitor prices for this item, so the lowest typical price across your other "
                                + "branches is the starting point.",
                bd(round2(anchor))));
        chain.add(new ChainStepDto("history", "Historical performance", Fmt.fmtMoney(observedMedian),
                eff(anchor, afterHistory),
                Fmt.groupInt(m.totalTransactions()) + " sales at this store over twelve months, between "
                        + Fmt.fmtMoney(m.observedMin()) + " and " + Fmt.fmtMoney(m.observedMax()) + ".",
                bd(round2(afterHistory))));
        chain.add(new ChainStepDto("demand", "Demand movement",
                (demandPct >= 0 ? "+" : "−") + Fmt.fixed(Math.abs(demandPct), 1) + "%",
                eff(afterRegion, afterDemand),
                m.demand() != null
                        ? m.demand().label() + ": selling " + Fmt.jsNum(m.demand().recentVelocity())
                                + "/week against an expected " + Fmt.jsNum(m.demand().expectedVelocity()) + "."
                        : "No demand signal at this store, so no adjustment was made.",
                bd(round2(afterDemand))));
        chain.add(new ChainStepDto("competition", "Competitor range",
                hasCompetitors ? Fmt.fmtMoney(competitorLow) + "–" + Fmt.fmtMoney(competitorHigh) : "No quotes",
                null,
                hasCompetitors ? "Where the rest of the market sits. The recommendation stays inside it."
                        : "Nothing scraped for this item yet.",
                bd(round2(afterDemand))));
        boolean rppless = m.msaMode().equals("off") || store == null || store.rpp() == null;
        chain.add(new ChainStepDto("region", "Regional adjustment · " + region.compassLabel(),
                rppless ? "None" : (m.msaMult() < 1 ? "−" : "+") + Fmt.fixed(Math.abs((m.msaMult() - 1) * 100), 1) + "%",
                eff(afterHistory, afterRegion),
                rppless
                        ? "This branch is priced nationally."
                        : m.msaMult() < 1
                                ? storeLabel + " sits in a busier market with more supply houses nearby, so the "
                                        + "price eases to win the sale."
                                : storeLabel + " has fewer competitors nearby than average, so the price lifts a "
                                        + "little.",
                bd(round2(afterRegion))));
        chain.add(new ChainStepDto("margin", "Margin target",
                Fmt.fixed(marginPercent(recommended, m.cost()), 1) + "%",
                eff(afterDemand, recommended),
                "Never below " + Fmt.fmtMoney(marginFloor) + " (25% minimum margin) and never above the "
                        + Fmt.fmtMoney(m.ceilingPrice()) + " market ceiling.",
                bd(recommended)));

        ChainStepDto finalStep = new ChainStepDto("final", "Recommended price", Fmt.fmtMoney(recommended),
                eff(m.currentPrice(), recommended),
                "Against the " + Fmt.fmtMoney(m.currentPrice()) + " charged today.", bd(recommended));

        double demandConf = m.demand() != null ? m.demand().confWeight() : 0.5;
        int confidence = (int) Math.round(clamp(58 + Math.min(18, m.competitors().size() * 2.2)
                + Math.min(14, m.totalTransactions() / 17.0) + demandConf * 8, 55, 97));

        Drift drift = priceDriftPct90(m, commodityKey);
        double marketToday = round2(anchor);
        double[] back = new double[12];
        back[11] = marketToday;
        double p = marketToday;
        for (int i = 11; i >= 1; i--) {
            double step = (drift.pct() * 0.5) / 12 / 100 + Seeded.randRange(key + ":h:" + i, "noise", -0.016, 0.016);
            p = p / (1 + step);
            back[i - 1] = round2(p);
        }
        List<TimelinePointDto> timeline = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            timeline.add(new TimelinePointDto(MONTHS[i], bd(back[i]), null, null, i == 11 ? "today" : "past"));
        }
        double d30 = round2(marketToday * (1 + (drift.pct() * 0.38) / 100));
        double d60 = round2(marketToday * (1 + (drift.pct() * 0.72) / 100));
        double d90 = round2(marketToday * (1 + (drift.pct() * 1.0) / 100));
        timeline.add(new TimelinePointDto("+30d", bd(d30), bd(round2(d30 * (1 - band(1)))), bd(round2(d30 * (1 + band(1)))), "future"));
        timeline.add(new TimelinePointDto("+60d", bd(d60), bd(round2(d60 * (1 - band(2)))), bd(round2(d60 * (1 + band(2)))), "future"));
        timeline.add(new TimelinePointDto("+90d", bd(d90), bd(round2(d90 * (1 - band(3)))), bd(round2(d90 * (1 + band(3)))), "future"));

        int waitDays = 14;
        double waitPrice = round2(recommended * (1 + (drift.pct() * 0.19) / 100));
        double extraPerUnit = round2(waitPrice - recommended);
        int monthlyUnits = monthlyUnitsFor(itemNumber, storeId, m.cost());
        boolean demandNotLow = m.demand() == null || !m.demand().level().equals("low");
        String risk = drift.pct() > 4 && demandNotLow ? "Low" : drift.pct() > 1.5 ? "Medium" : "High";
        double extraPct = (extraPerUnit / recommended) * 100;
        boolean worthWaiting = extraPct > 1.2 && !risk.equals("High");
        String driverText = drift.driver().isEmpty() ? drift.driver()
                : Character.toUpperCase(drift.driver().charAt(0)) + drift.driver().substring(1);
        String reason;
        if (worthWaiting) {
            reason = driverText + " and " + (m.demand() != null ? m.demand().label().toLowerCase(java.util.Locale.ROOT) : "steady demand")
                    + " point to " + Fmt.fmtMoney(waitPrice) + " in two weeks: " + Fmt.fmtMoney(extraPerUnit)
                    + " more a unit, with a " + risk.toLowerCase(java.util.Locale.ROOT) + " risk of the move not arriving.";
        } else if (extraPerUnit <= 0) {
            reason = "The market is not expected to rise from here" + (drift.pct() < -1 ? " - if anything it softens" : "")
                    + ". Waiting only costs turns. Sell now.";
        } else {
            reason = "Waiting two weeks adds an expected " + Fmt.fmtMoney(extraPerUnit) + " a unit ("
                    + Fmt.fixed(extraPct, 1) + "%), " + (risk.equals("High") ? "with a high risk it never arrives"
                            : "too little to hold stock for") + ". Take the margin now.";
        }
        NowVsWaitDto nowVsWait = new NowVsWaitDto(worthWaiting ? "wait" : "now", reason,
                new NowSummaryDto(bd(recommended), bd(marginPercent(recommended, m.cost())), bd(round1(demandPct * 4))),
                new WaitSummaryDto(waitDays, bd(waitPrice), bd(extraPerUnit), bd(round2(extraPerUnit * (monthlyUnits / 2.0))), risk));

        Inventory inv = inventoryFor(itemNumber, storeId, monthlyUnits);
        double elasticityCoefficient = elasticity.getElasticityModel(itemNumber, "sell", storeId).coefficient().doubleValue();

        double upliftPerUnit = round2(recommended - m.currentPrice());
        double upliftPct = round1((upliftPerUnit / m.currentPrice()) * 100);

        return new SellIntelDto(itemNumber, storeId, true, name, itemNumber, storeLabel, region.compassLabel(),
                category, bd(m.cost()), bd(m.currentPrice()), bd(round2(anchor)), bd(recommended),
                bd(m.aggressivePrice()), bd(marginFloor), bd(m.ceilingPrice()),
                bd(marginPercent(m.currentPrice(), m.cost())), bd(marginPercent(recommended, m.cost())),
                bd(upliftPerUnit), bd(upliftPct), confidence, confidenceLabel(confidence), m.competitors().size(),
                hasCompetitors ? bd(competitorLow) : null, hasCompetitors ? bd(competitorHigh) : null,
                bd(demandPct), m.demand() != null ? m.demand().label() : "Stable demand",
                bd(round2(afterRegion - afterHistory)), chain, finalStep, timeline,
                new ForecastSummaryDto(bd(d30), bd(d60), bd(d90), bd(drift.pct()), drift.driver()), nowVsWait,
                bd(elasticityCoefficient), monthlyUnits, monthlyUnits * 12, inv.units(), bd(round2(inv.units() * m.cost())),
                bd(inv.weeksOfCover()), bd(round2(upliftPerUnit * monthlyUnits)));
    }

    private static double band(int k) {
        return 0.016 + 0.014 * k;
    }

    /** Signed dollar delta between two running prices in the chain, or {@code ±$0.00} if negligible. */
    private static String eff(double from, double to) {
        double d = to - from;
        if (Math.abs(d) < 0.005) {
            return "±" + Fmt.fmtMoney(0);
        }
        return (d > 0 ? "+" : "−") + Fmt.fmtMoney(Math.abs(d));
    }

    /** {@code runScenario}: a price move measured against today's price and volume. */
    public static ScenarioResultDto runScenario(SellIntelDto intel, double pct, double basePrice) {
        int baseUnits = intel.annualUnits();
        double baseRevenue = basePrice * baseUnits;
        double baseProfit = (basePrice - intel.cost().doubleValue()) * baseUnits;
        double baseMargin = marginPercent(basePrice, intel.cost().doubleValue());

        double price = round2(basePrice * (1 + pct / 100));
        double qtyRatio = Math.pow(1 + pct / 100, intel.elasticity().doubleValue());
        int units = (int) Math.round(baseUnits * qtyRatio);
        double revenue = round2(price * units);
        double profit = round2((price - intel.cost().doubleValue()) * units);
        double marginPct = marginPercent(price, intel.cost().doubleValue());

        return new ScenarioResultDto(bd(pct), bd(price), units, bd(revenue), bd(round2(revenue - baseRevenue)),
                bd(round1(((revenue - baseRevenue) / Math.max(1, baseRevenue)) * 100)), bd(marginPct),
                bd(round1(marginPct - baseMargin)), bd(round1((qtyRatio - 1) * 100)), bd(profit),
                bd(round2(profit - baseProfit)),
                bd(round1(((profit - baseProfit) / Math.max(1, Math.abs(baseProfit))) * 100)));
    }

    public static ScenarioResultDto runScenario(SellIntelDto intel, double pct) {
        return runScenario(intel, pct, intel.currentPrice().doubleValue());
    }

    /**
     * The sell page's {@code answerFor} guardrail adjustment: when a guardrail binds, the
     * recommended price and everything derived from it at current volume move to the
     * adjusted price. Everything else on the intel - the chain, the timeline, the forecast -
     * stays as {@code getSellIntel} computed it; only the five fields {@code answerFor}
     * itself overrides change.
     */
    public static SellIntelDto withAdjustedPrice(SellIntelDto intel, java.math.BigDecimal adjustedPrice) {
        if (adjustedPrice.compareTo(intel.recommended()) == 0) {
            return intel;
        }
        double price = adjustedPrice.doubleValue();
        double cost = intel.cost().doubleValue();
        double currentPrice = intel.currentPrice().doubleValue();
        double marginPct = price == 0 ? 0 : round1(((price - cost) / price) * 100);
        double uplift = round2(price - currentPrice);
        double upliftPct = currentPrice == 0 ? 0 : round1((uplift / currentPrice) * 100);
        double monthlyOpp = round2(uplift * intel.monthlyUnits());
        return new SellIntelDto(intel.itemNumber(), intel.storeId(), intel.priceable(), intel.name(),
                intel.description(), intel.storeLabel(), intel.regionLabel(), intel.category(), intel.cost(),
                intel.currentPrice(), intel.marketPrice(), adjustedPrice, intel.stretchPrice(), intel.marginFloor(),
                intel.ceiling(), intel.currentMarginPct(), bd(marginPct), bd(uplift), bd(upliftPct),
                intel.confidence(), intel.confidenceLabel(), intel.competitorCount(), intel.competitorLow(),
                intel.competitorHigh(), intel.demandPct(), intel.demandLabel(), intel.regionalAdj(), intel.chain(),
                intel.finalStep(), intel.timeline(), intel.forecast(), intel.nowVsWait(), intel.elasticity(),
                intel.monthlyUnits(), intel.annualUnits(), intel.inventoryUnits(), intel.inventoryValue(),
                intel.weeksOfCover(), bd(monthlyOpp));
    }
}
