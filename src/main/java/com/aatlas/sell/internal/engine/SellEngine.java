package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.clamp;
import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Catalogue.RegionRef;
import com.aatlas.history.Catalogue.StoreRef;
import com.aatlas.history.PricingMath;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesHistory.MonthPoint;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.dto.SellDtos.ChainStepDto;
import com.aatlas.sell.internal.dto.SellDtos.ForecastSummaryDto;
import com.aatlas.sell.internal.dto.SellDtos.NowSummaryDto;
import com.aatlas.sell.internal.dto.SellDtos.NowVsWaitDto;
import com.aatlas.sell.internal.dto.SellDtos.ScenarioResultDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import com.aatlas.sell.internal.dto.SellDtos.TimelinePointDto;
import com.aatlas.sell.internal.dto.SellDtos.WaitSummaryDto;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/intel/sell.ts}: {@code getSellIntel} and {@code runScenario}. The
 * pricing engine ({@link PricingEngine}) resolves the number from {@code history}; this
 * turns it into a decision - the chain (from {@code history.PricingMath.recommend}'s own
 * running values, so every multiplier appears once), the twelve-month timeline, sell-now-
 * or-wait, and volume/inventory.
 */
@Component
public class SellEngine {

    private final CatalogGateway catalog;
    private final PricingEngine pricing;
    private final SalesHistory sales;
    private final AatlasClock clock;

    public SellEngine(CatalogGateway catalog, PricingEngine pricing, SalesHistory sales, AatlasClock clock) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.sales = sales;
        this.clock = clock;
    }

    public static String confidenceLabel(int n) {
        return n >= 85 ? "High" : n >= 70 ? "Medium" : "Low";
    }

    private RegionRef resolveRegion(StoreRef store) {
        String regionKey = store != null && store.regionKey() != null ? store.regionKey() : "unassigned";
        return catalog.regionOf(regionKey)
                .or(() -> catalog.allRegions().stream().findFirst())
                .orElse(new RegionRef(regionKey, regionKey, regionKey));
    }

    private static double d(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    private static BigDecimal money(BigDecimal v, double factor) {
        return v == null ? null : v.multiply(BigDecimal.valueOf(factor)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public SellIntelDto getSellIntel(String itemNumber, String storeId) {
        LocalDate today = clock.today();
        PricingModel m = pricing.getPricingModel(itemNumber, storeId);
        var product = catalog.findProduct(itemNumber).orElse(null);
        var store = (storeId == null || storeId.isBlank()) ? null : catalog.findStore(storeId).orElse(null);
        RegionRef region = resolveRegion(store);

        String name = product != null ? product.shortName() : itemNumber;
        String category = product != null ? product.category() : null;
        String storeLabel = store != null ? store.label() : (storeId == null ? "" : storeId);

        Map<String, String> sources = new LinkedHashMap<>();
        if (m.costSource() != null) {
            sources.put("cost", m.costSource());
        }
        if (m.currentPriceSource() != null) {
            sources.put("currentPrice", m.currentPriceSource());
        }
        if (m.anchorSource() != null) {
            sources.put("anchor", m.anchorSource());
        }
        if (m.units90() != null) {
            sources.put("units", "sales-90d");
        }
        if (m.onHandUnits() != null) {
            sources.put("inventory", "inventory");
        }

        List<String> locked = new ArrayList<>(m.locked());

        if (!m.priceable()) {
            ChainStepDto emptyFinal = new ChainStepDto("final", "Recommended price", "—", null, "", null);
            return new SellIntelDto(itemNumber, storeId, false, name, itemNumber, storeLabel, region.shortLabel(),
                    category, m.cost(), null, null, null, null, null, null, null, null, null, null,
                    0, "Low", m.competitors().size(), null, null, null, "No signal", null, List.of(), emptyFinal,
                    List.of(),
                    new ForecastSummaryDto(null, null, null, null, ""),
                    new NowVsWaitDto("now", "", new NowSummaryDto(null, null, null),
                            new WaitSummaryDto(14, null, null, null, "High")),
                    m.beta(), 0, 0, null, null, null, null, sources, List.copyOf(locked), null);
        }

        PricingMath.Recommendation rec = m.recommendation();
        BigDecimal recommended = rec != null ? rec.optimal() : null;
        BigDecimal aggressive = rec != null ? rec.aggressive() : null;
        BigDecimal marketAnchor = rec != null ? rec.anchor() : m.anchorValue();
        BigDecimal ownRef = m.ownRef();

        List<ChainStepDto> chain = new ArrayList<>();
        if (rec != null) {
            boolean hasAnchor = rec.anchor() != null;
            chain.add(new ChainStepDto("market", "Market benchmark",
                    hasAnchor ? Fmt.fmtMoney(d(rec.anchor())) : "No market anchor", null,
                    hasAnchor ? Fmt.groupInt(m.anchorObservations()) + " observation(s), source " + rec.anchorSource() + "."
                            : "No competitor, peer or benchmark anchor for this pair.",
                    rec.anchor()));
            chain.add(new ChainStepDto("history", "Blend with own reference", Fmt.fmtMoney(d(rec.base())),
                    eff(rec.anchor(), rec.base()),
                    ownRef != null
                            ? "Own last price " + Fmt.fmtMoney(d(ownRef)) + ", blended at weight "
                                    + Fmt.fixed(rec.anchorWeight(), 2) + " on the market anchor."
                            : "No sales history at this store yet; the market anchor alone is the base.",
                    rec.base()));
            chain.add(new ChainStepDto("demand", "Demand movement",
                    m.demand() != null ? (m.demand().movePercent().signum() >= 0 ? "+" : "−")
                            + Fmt.fixed(Math.abs(d(m.demand().movePercent())), 1) + "%" : "No signal",
                    eff(rec.base(), rec.afterDemand()),
                    m.demand() != null
                            ? m.demand().label() + ": " + Fmt.jsNum(d(m.demand().recentVelocity())) + "/wk against "
                                    + Fmt.jsNum(d(m.demand().expectedVelocity())) + "/wk expected."
                            : "No demand signal at this store, so no adjustment was made.",
                    rec.afterDemand()));
            chain.add(new ChainStepDto("commodity", "Commodity pass-through",
                    m.commodityPct90() != null ? Fmt.fixed(d(m.commodityPct90()), 1) + "%" : "No exposure",
                    eff(rec.afterDemand() != null ? rec.afterDemand() : rec.base(), rec.afterCommodity()),
                    m.commodityPct90() != null
                            ? (m.commodityLabel() != null ? m.commodityLabel() : "Commodity index")
                                    + " (reference, as of " + m.commodityAsOf() + ")."
                            : "This item carries no commodity exposure.",
                    rec.afterCommodity()));
            boolean rppless = "off".equals(m.msaMode());
            chain.add(new ChainStepDto("region", "Regional adjustment · " + region.shortLabel(),
                    rppless ? "None" : (d(m.msaMult()) < 1 ? "−" : "+")
                            + Fmt.fixed(Math.abs((d(m.msaMult()) - 1) * 100), 1) + "%",
                    eff(rec.afterCommodity() != null ? rec.afterCommodity() : rec.base(), rec.afterRegion()),
                    rppless ? "This branch is priced nationally, or the anchor is already local (competitor prices)."
                            : storeLabel + " carries a regional price-parity index.",
                    rec.afterRegion()));
            chain.add(new ChainStepDto("margin", "Margin target",
                    marginPctText(recommended, m.cost()),
                    eff(lastNonNull(rec), recommended),
                    "Never below " + moneyOrDash(rec.floor()) + " and never above " + moneyOrDash(rec.ceiling()) + ".",
                    recommended));
        }
        ChainStepDto finalStep = new ChainStepDto("final", "Recommended price",
                recommended != null ? Fmt.fmtMoney(d(recommended)) : "—", eff(m.currentPrice(), recommended),
                m.currentPrice() != null ? "Against the " + Fmt.fmtMoney(d(m.currentPrice())) + " charged today." : "",
                recommended);

        double demandConf = m.demand() != null ? d(m.demand().confWeight()) : 0.5;
        int confidence = (int) Math.round(clamp(58 + Math.min(18, m.competitors().size() * 2.2)
                + Math.min(14, m.totalTransactions() / 17.0) + demandConf * 8
                + (SalesHistory.Elasticity.ITEM_STORE.equals(m.elasticityBasis()) ? 4 : 0), 55, 97));

        // -- timeline: real monthly average prices, gaps null, last non-null = own reference --
        List<MonthPoint> months = sales.monthly(m.productId(), m.storeUuid(), 12, today);
        List<TimelinePointDto> timeline = new ArrayList<>();
        BigDecimal lastKnown = null;
        for (MonthPoint mp : months) {
            String label = mp.month().getMonth().getDisplayName(TextStyle.SHORT, Locale.US);
            if (mp.avgPrice() != null) {
                lastKnown = mp.avgPrice();
            }
            timeline.add(new TimelinePointDto(label, mp.avgPrice(), null, null, "past"));
        }
        if (!timeline.isEmpty()) {
            TimelinePointDto last = timeline.get(timeline.size() - 1);
            timeline.set(timeline.size() - 1, new TimelinePointDto(last.label(), last.value(), null, null, "today"));
        }
        // A forecast needs an actual monthly sales series to project from - a price-list-only
        // pair (no sales yet) gets a currentPrice but never a forecast; asking for one would
        // manufacture a number from nothing.
        boolean hasSalesSeries = lastKnown != null;
        BigDecimal forecastBase = !hasSalesSeries ? null : (marketAnchor != null ? marketAnchor : lastKnown);

        double movePercent = m.demand() != null ? d(m.demand().movePercent()) : 0;
        BigDecimal driftPct90 = forecastBase == null ? null : PricingMath.driftPct90(m.commodityPct90(), movePercent);
        String driver;
        if (forecastBase == null) {
            driver = "";
            locked.add("forecast");
        } else {
            double k = m.commodityPct90() != null ? d(m.commodityPct90()) : 0;
            driver = Math.abs(k) >= Math.abs(movePercent)
                    ? (m.commodityLabel() != null
                            ? m.commodityLabel() + " (reference, as of " + m.commodityAsOf() + ")" : "Commodity index")
                    : (m.demand() != null ? m.demand().label() : "Local demand");
        }
        BigDecimal d30 = forecastBase == null ? null
                : forecastBase.multiply(BigDecimal.ONE.add(driftPct90.multiply(BigDecimal.valueOf(0.38))
                        .divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP))).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal d60 = forecastBase == null ? null
                : forecastBase.multiply(BigDecimal.ONE.add(driftPct90.multiply(BigDecimal.valueOf(0.72))
                        .divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP))).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal d90 = forecastBase == null ? null
                : forecastBase.multiply(BigDecimal.ONE.add(driftPct90
                        .divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP))).setScale(2, java.math.RoundingMode.HALF_UP);
        if (d30 != null) {
            timeline.add(new TimelinePointDto("+30d", d30, band(d30, 1), band(d30, -1), "future"));
            timeline.add(new TimelinePointDto("+60d", d60, band(d60, 2), band(d60, -2), "future"));
            timeline.add(new TimelinePointDto("+90d", d90, band(d90, 3), band(d90, -3), "future"));
        }

        // -- volume / inventory --------------------------------------------------------------
        int monthlyUnits;
        if (m.units90() != null) {
            monthlyUnits = (int) Math.round(m.units90().doubleValue() / 3);
        } else if (m.units12m() != null && m.units12m().signum() > 0) {
            monthlyUnits = (int) Math.round(m.units12m().doubleValue() / 12);
        } else {
            monthlyUnits = 0;
        }
        int annualUnits = m.units12m() != null ? (int) Math.round(m.units12m().doubleValue()) : 0;

        Integer inventoryUnits = m.onHandUnits() != null ? m.onHandUnits().setScale(0, java.math.RoundingMode.HALF_UP).intValue() : null;
        BigDecimal weeksOfCover = PricingMath.weeksOfCover(m.onHandUnits(), m.units90());
        BigDecimal inventoryValue = (m.onHandUnits() != null && m.cost() != null)
                ? m.onHandUnits().multiply(m.cost()).setScale(2, java.math.RoundingMode.HALF_UP) : null;

        BigDecimal upliftPerUnit = (recommended != null && m.currentPrice() != null)
                ? recommended.subtract(m.currentPrice()).setScale(2, java.math.RoundingMode.HALF_UP) : null;
        BigDecimal upliftPct = PricingMath.pct(upliftPerUnit, m.currentPrice());
        BigDecimal monthlyOpportunity = upliftPerUnit == null ? null
                : upliftPerUnit.multiply(BigDecimal.valueOf(monthlyUnits)).setScale(2, java.math.RoundingMode.HALF_UP);

        // -- now vs wait -----------------------------------------------------------------------
        int waitDays = 14;
        BigDecimal waitPrice = (recommended != null && driftPct90 != null)
                ? recommended.multiply(BigDecimal.ONE.add(driftPct90.multiply(BigDecimal.valueOf(0.19))
                        .divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP)))
                        .setScale(2, java.math.RoundingMode.HALF_UP)
                : recommended;
        BigDecimal extraPerUnit = (waitPrice != null && recommended != null)
                ? waitPrice.subtract(recommended).setScale(2, java.math.RoundingMode.HALF_UP) : null;
        double driftD = driftPct90 == null ? 0 : driftPct90.doubleValue();
        boolean demandNotLow = m.demand() == null || !"low".equals(m.demand().level());
        String risk = driftD > 4 && demandNotLow ? "Low" : driftD > 1.5 ? "Medium" : "High";
        double extraPct = (extraPerUnit == null || recommended == null || recommended.signum() == 0) ? 0
                : extraPerUnit.doubleValue() / recommended.doubleValue() * 100;
        boolean worthWaiting = extraPct > 1.2 && !"High".equals(risk) && extraPerUnit != null;
        String reason;
        if (extraPerUnit == null) {
            reason = "Not enough data to project a price move. Sell at the recommendation.";
        } else if (worthWaiting) {
            reason = driver + " points to " + Fmt.fmtMoney(d(waitPrice)) + " in two weeks: "
                    + Fmt.fmtMoney(d(extraPerUnit)) + " more a unit, with a " + risk.toLowerCase(Locale.ROOT)
                    + " risk of the move not arriving.";
        } else if (extraPerUnit.signum() <= 0) {
            reason = "The market is not expected to rise from here. Waiting only costs turns. Sell now.";
        } else {
            reason = "Waiting two weeks adds an expected " + Fmt.fmtMoney(d(extraPerUnit)) + " a unit ("
                    + Fmt.fixed(extraPct, 1) + "%), too little to hold stock for. Take the margin now.";
        }
        NowVsWaitDto nowVsWait = new NowVsWaitDto(worthWaiting ? "wait" : "now", reason,
                new NowSummaryDto(recommended, PricingMath.marginPct(recommended, m.cost()),
                        driftPct90 == null ? null : round1(driftD * 4) == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(round1(driftD * 4))),
                new WaitSummaryDto(waitDays, waitPrice, extraPerUnit,
                        extraPerUnit == null ? null : extraPerUnit.multiply(BigDecimal.valueOf(monthlyUnits / 2.0))
                                .setScale(2, java.math.RoundingMode.HALF_UP),
                        risk));

        double[] compPrices = m.competitors().stream().map(PricingTypes.Competitor::price)
                .filter(java.util.Objects::nonNull).mapToDouble(BigDecimal::doubleValue).toArray();
        boolean hasCompetitors = compPrices.length > 0;
        BigDecimal competitorLow = hasCompetitors ? bd(round2(java.util.Arrays.stream(compPrices).min().orElse(0))) : null;
        BigDecimal competitorHigh = hasCompetitors ? bd(round2(java.util.Arrays.stream(compPrices).max().orElse(0))) : null;

        return new SellIntelDto(itemNumber, storeId, true, name, itemNumber, storeLabel, region.shortLabel(),
                category, m.cost(), m.currentPrice(), marketAnchor, recommended, aggressive, rec != null ? rec.floor() : null,
                rec != null ? rec.ceiling() : null,
                PricingMath.marginPct(m.currentPrice(), m.cost()), PricingMath.marginPct(recommended, m.cost()),
                upliftPerUnit, upliftPct, confidence, confidenceLabel(confidence), m.competitors().size(),
                competitorLow, competitorHigh, m.demand() != null ? m.demand().movePercent() : null,
                m.demand() != null ? m.demand().label() : "Stable demand",
                eff2(rec != null ? rec.afterCommodity() : null, rec != null ? rec.afterRegion() : null), chain,
                finalStep, timeline,
                new ForecastSummaryDto(d30, d60, d90, driftPct90, driver), nowVsWait,
                m.beta(), monthlyUnits, annualUnits, inventoryUnits, inventoryValue, weeksOfCover,
                monthlyOpportunity, sources, List.copyOf(locked), m.inventoryAsOf());
    }

    private static BigDecimal band(BigDecimal center, int k) {
        if (center == null) {
            return null;
        }
        double factor = 1 + Math.signum(k) * (0.016 + 0.014 * Math.abs(k));
        return center.multiply(BigDecimal.valueOf(factor)).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static BigDecimal lastNonNull(PricingMath.Recommendation rec) {
        if (rec.afterRegion() != null) {
            return rec.afterRegion();
        }
        if (rec.afterCommodity() != null) {
            return rec.afterCommodity();
        }
        if (rec.afterDemand() != null) {
            return rec.afterDemand();
        }
        return rec.base();
    }

    private static String marginPctText(BigDecimal price, BigDecimal cost) {
        BigDecimal m = PricingMath.marginPct(price, cost);
        return m == null ? "—" : Fmt.fixed(m.doubleValue(), 1) + "%";
    }

    private static String moneyOrDash(BigDecimal v) {
        return v == null ? "—" : Fmt.fmtMoney(v.doubleValue());
    }

    /** Signed dollar delta between two running prices in the chain, or {@code null} if either side is absent. */
    private static String eff(BigDecimal from, BigDecimal to) {
        if (from == null || to == null) {
            return null;
        }
        double delta = to.doubleValue() - from.doubleValue();
        if (Math.abs(delta) < 0.005) {
            return "±" + Fmt.fmtMoney(0);
        }
        return (delta > 0 ? "+" : "−") + Fmt.fmtMoney(Math.abs(delta));
    }

    private static BigDecimal eff2(BigDecimal from, BigDecimal to) {
        if (from == null || to == null) {
            return null;
        }
        return to.subtract(from).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** {@code runScenario}: a price move measured against today's price and volume. */
    public static ScenarioResultDto runScenario(SellIntelDto intel, double pct, double basePrice) {
        int baseUnits = intel.annualUnits();
        double cost = intel.cost() == null ? 0 : intel.cost().doubleValue();
        double baseRevenue = basePrice * baseUnits;
        double baseProfit = (basePrice - cost) * baseUnits;
        double baseMargin = basePrice == 0 ? 0 : (basePrice - cost) / basePrice * 100;

        double price = round2(basePrice * (1 + pct / 100));
        double elasticity = intel.elasticity() == null ? -1.2 : intel.elasticity().doubleValue();
        double qtyRatio = Math.pow(1 + pct / 100, elasticity);
        int units = (int) Math.round(baseUnits * qtyRatio);
        double revenue = round2(price * units);
        double profit = round2((price - cost) * units);
        double marginPct = price == 0 ? 0 : round2((price - cost) / price * 100);

        return new ScenarioResultDto(bd(pct), bd(price), units, bd(revenue), bd(round2(revenue - baseRevenue)),
                bd(round1(((revenue - baseRevenue) / Math.max(1, baseRevenue)) * 100)), bd(marginPct),
                bd(round1(marginPct - baseMargin)), bd(round1((qtyRatio - 1) * 100)), bd(profit),
                bd(round2(profit - baseProfit)),
                bd(round1(((profit - baseProfit) / Math.max(1, Math.abs(baseProfit))) * 100)));
    }

    public static ScenarioResultDto runScenario(SellIntelDto intel, double pct) {
        return runScenario(intel, pct, intel.currentPrice() == null ? 0 : intel.currentPrice().doubleValue());
    }

    /**
     * The sell page's {@code answerFor} guardrail adjustment: when a guardrail binds, the
     * recommended price and everything derived from it at current volume move to the
     * adjusted price. Everything else on the intel - the chain, the timeline, the forecast -
     * stays as {@code getSellIntel} computed it; only the five fields {@code answerFor}
     * itself overrides change.
     */
    public static SellIntelDto withAdjustedPrice(SellIntelDto intel, BigDecimal adjustedPrice) {
        if (adjustedPrice == null || intel.recommended() == null || adjustedPrice.compareTo(intel.recommended()) == 0) {
            return intel;
        }
        BigDecimal cost = intel.cost();
        BigDecimal currentPrice = intel.currentPrice();
        BigDecimal marginPct = PricingMath.marginPct(adjustedPrice, cost);
        BigDecimal uplift = currentPrice == null ? null
                : adjustedPrice.subtract(currentPrice).setScale(2, java.math.RoundingMode.HALF_UP);
        BigDecimal upliftPct = PricingMath.pct(uplift, currentPrice);
        BigDecimal monthlyOpp = uplift == null ? null
                : uplift.multiply(BigDecimal.valueOf(intel.monthlyUnits())).setScale(2, java.math.RoundingMode.HALF_UP);
        return new SellIntelDto(intel.itemNumber(), intel.storeId(), intel.priceable(), intel.name(),
                intel.description(), intel.storeLabel(), intel.regionLabel(), intel.category(), intel.cost(),
                intel.currentPrice(), intel.marketPrice(), adjustedPrice, intel.stretchPrice(), intel.marginFloor(),
                intel.ceiling(), intel.currentMarginPct(), marginPct, uplift, upliftPct,
                intel.confidence(), intel.confidenceLabel(), intel.competitorCount(), intel.competitorLow(),
                intel.competitorHigh(), intel.demandPct(), intel.demandLabel(), intel.regionalAdj(), intel.chain(),
                intel.finalStep(), intel.timeline(), intel.forecast(), intel.nowVsWait(), intel.elasticity(),
                intel.monthlyUnits(), intel.annualUnits(), intel.inventoryUnits(), intel.inventoryValue(),
                intel.weeksOfCover(), monthlyOpp, intel.sources(), intel.locked(), intel.inventoryAsOf());
    }
}
