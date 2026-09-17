package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.clamp;
import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.decisions.DealSummaries.Adoption;
import com.aatlas.history.Catalogue.CustomerRef;
import com.aatlas.history.PricingMath;
import com.aatlas.history.Window;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import com.aatlas.sell.internal.dto.Sell2Dtos.AtpOrderDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.CustomerProfileDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.GuardrailCheckDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.HoldDecisionDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.LiquidationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.ScorePartDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SellDecisionScoreDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SellScenarioOptionDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SellWhatIfDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SpeedPricingDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SpeedTierDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.ToneLineDto;
import com.aatlas.sell.internal.dto.SellDtos.ScenarioResultDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import com.aatlas.sell.internal.policy.GuardrailValues;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Port of {@code src/lib/intel/sell2.ts}, over real inputs from {@link SellEngine}. */
@Component
public class Sell2Engine {

    public record ProfileMeta(String label, List<String> traits, String fulfilment, String priceSensitivity) {
    }

    private static final java.util.Map<String, ProfileMeta> PROFILES = java.util.Map.of(
            "urgent", new ProfileMeta("Urgent buyer",
                    List.of("High urgency", "Low price sensitivity", "Buys on availability"), "urgent", "Low"),
            "value", new ProfileMeta("Value buyer",
                    List.of("High price sensitivity", "Flexible delivery", "Shops the market"), "flexible", "High"),
            "enterprise", new ProfileMeta("Enterprise",
                    List.of("High volume", "Hard SLA", "Contract pricing"), "urgent", "Low"),
            "repeat", new ProfileMeta("Repeat buyer",
                    List.of("High frequency", "Predictable demand", "Price-aware"), "standard", "Medium"));

    public static final List<SellScenarioOptionDto> SELL_SCENARIOS = List.of(
            new SellScenarioOptionDto("price-up-5", "I raise the price 5%"),
            new SellScenarioOptionDto("demand-down-10", "Demand falls 10%"),
            new SellScenarioOptionDto("competitor-cut-5", "A competitor cuts price 5%"),
            new SellScenarioOptionDto("hold-30", "I hold inventory 30 days"));

    private final CatalogGateway catalog;
    private final DealSummaries deals;
    private final AatlasClock clock;

    public Sell2Engine(CatalogGateway catalog, DealSummaries deals, AatlasClock clock) {
        this.catalog = catalog;
        this.deals = deals;
        this.clock = clock;
    }

    private static double d(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    public CustomerProfileDto customerProfile(CustomerRef c) {
        String profile = c != null ? c.profile() : "repeat";
        int slaDays = c != null ? c.slaDays() : 7;
        ProfileMeta meta = PROFILES.getOrDefault(profile, PROFILES.get("repeat"));
        return new CustomerProfileDto(profile, slaDays, meta.label(), meta.fulfilment(), meta.traits());
    }

    // -- Speed premium ----------------------------------------------------------------------

    public SpeedPricingDto speedPricing(SellIntelDto intel, GuardrailValues g) {
        double demandPct = d(intel.demandPct());
        double demandBoost = demandPct > 0 ? 2.2 : demandPct < 0 ? -1 : 0.8;
        double rawPremium = round1(clamp(5.2 + demandBoost + (intel.confidence() - 75) / 25.0, 3, 14));
        double premiumPct = Math.min(rawPremium, g.maxSpeedPremiumPct());
        boolean cappedByPolicy = rawPremium > g.maxSpeedPremiumPct();
        double cost = d(intel.cost());
        double recommended = d(intel.recommended());
        double floor = intel.cost() != null ? cost / (1 - g.minMarginPct() / 100) : 0;
        double urgentPrice = round2(recommended * (1 + premiumPct / 100));
        double flexiblePrice = round2(Math.max(floor, recommended * (1 - 3.5 / 100)));

        List<CustomerRef> customers = catalog.allCustomers();

        SpeedTierDto urgent = tier(intel, "urgent", "Urgent", "Same-day or 48-hour fulfilment", urgentPrice, customers);
        SpeedTierDto standard = tier(intel, "standard", "Standard", "3-7 days", recommended, customers);
        SpeedTierDto flexible = tier(intel, "flexible", "Flexible", "10 days or more", flexiblePrice, customers);

        String explanation = "Customer urgency and SLA requirements support a " + Fmt.fixed(premiumPct, 1)
                + "% speed premium" + (cappedByPolicy ? ", capped by your " + Fmt.jsNum(g.maxSpeedPremiumPct())
                        + "% policy limit" : "")
                + ". " + (demandPct > 0 ? "Demand is rising, so availability itself is worth paying for."
                        : "Flexible customers are offered a little less in exchange for a wider delivery window.");

        return new SpeedPricingDto(List.of(urgent, standard, flexible), bd(premiumPct), cappedByPolicy, explanation);
    }

    private SpeedTierDto tier(SellIntelDto intel, String key, String label, String requirement, double price,
            List<CustomerRef> customers) {
        double recommended = d(intel.recommended());
        List<String> names = customers.stream()
                .filter(c -> customerProfile(c).fulfilment().equals(key))
                .map(CustomerRef::name)
                .toList();
        BigDecimal premiumPct = recommended == 0 ? null : bd(round1(((price - recommended) / recommended) * 100));
        return new SpeedTierDto(key, label, requirement, bd(price), premiumPct,
                bd(round2(price - recommended)), PricingMath.marginPct(bd(price), intel.cost()), names);
    }

    // -- Hold vs sell -------------------------------------------------------------------------

    public HoldDecisionDto holdVsSell(SellIntelDto intel, int days, String commodityKey) {
        double recommended = d(intel.recommended());
        BigDecimal driftPct90 = intel.forecast() != null ? intel.forecast().driftPct90() : null;
        double share = days / 90.0;
        BigDecimal priceLater = driftPct90 == null ? null
                : bd(round2(recommended * (1 + (driftPct90.doubleValue() * share) / 100)));
        BigDecimal appreciation = priceLater == null ? null : bd(round2(priceLater.doubleValue() - recommended));

        BigDecimal holdingCost = intel.cost() == null ? null
                : bd(round2(d(intel.cost()) * (0.22 / 12) * (days / 30.0) + d(intel.cost()) * 0.004));
        CommodityTrend commodity = catalog.commodityTrend(commodityKey);
        BigDecimal depreciationRisk = intel.cost() == null ? null
                : bd(round2(d(intel.cost()) * (0.006 + (commodity.pct90() < 0 ? Math.abs(commodity.pct90()) / 100 : 0) * 0.5)));

        String demandLabel = intel.demandLabel();
        String demandUncertainty = "High demand".equals(demandLabel) ? "Low"
                : "Low demand".equals(demandLabel) ? "High" : "Medium";
        BigDecimal uncertaintyCost = appreciation == null ? null
                : bd(round2(Math.max(0, appreciation.doubleValue())
                        * (demandUncertainty.equals("Low") ? 0.1 : demandUncertainty.equals("Medium") ? 0.3 : 0.55)));

        BigDecimal net = (appreciation == null || holdingCost == null || depreciationRisk == null || uncertaintyCost == null)
                ? null
                : bd(round2(appreciation.doubleValue() - holdingCost.doubleValue() - depreciationRisk.doubleValue()
                        - uncertaintyCost.doubleValue()));
        boolean hold = net != null && net.doubleValue() > 0.05 && !demandUncertainty.equals("High");

        Integer inventoryUnits = intel.inventoryUnits();
        BigDecimal netTotal = (net != null && inventoryUnits != null) ? bd(round2(net.doubleValue() * inventoryUnits))
                : null;

        String reason;
        if (net == null) {
            reason = "Not enough data (cost or a price forecast is missing) to size a hold-vs-sell decision "
                    + "beyond a per-unit view.";
        } else if (hold) {
            reason = "The forecast adds " + money(appreciation.doubleValue()) + " a unit in " + days + " days. After "
                    + money(holdingCost.doubleValue()) + " of holding cost, " + money(depreciationRisk.doubleValue())
                    + " of depreciation risk and a " + demandUncertainty.toLowerCase(Locale.ROOT)
                    + " demand discount, " + money(net.doubleValue()) + " a unit is still left"
                    + (netTotal != null ? ": " + money(netTotal.doubleValue()) + " on the stock you hold." : ".");
        } else if (appreciation != null && appreciation.signum() <= 0) {
            reason = "The market is not expected to rise in " + days + " days. Holding only adds "
                    + money(holdingCost.doubleValue() + depreciationRisk.doubleValue()) + " a unit of cost. Sell now.";
        } else {
            reason = "The forecast adds " + money(appreciation.doubleValue()) + " a unit, but holding costs "
                    + money(holdingCost.doubleValue()) + ", depreciation risk " + money(depreciationRisk.doubleValue())
                    + " and " + demandUncertainty.toLowerCase(Locale.ROOT) + " demand uncertainty eat it. Net "
                    + money(net.doubleValue()) + ": sell now.";
        }

        List<ToneLineDto> lines = new ArrayList<>();
        lines.add(new ToneLineDto("Selling price now", Fmt.fmtMoney(recommended), null));
        lines.add(new ToneLineDto("Forecast in " + days + " days",
                priceLater != null ? Fmt.fmtMoney(priceLater.doubleValue()) : "—",
                appreciation != null && appreciation.signum() >= 0 ? "good" : appreciation != null ? "bad" : "muted"));
        lines.add(new ToneLineDto("Potential additional margin", appreciation != null ? money(appreciation.doubleValue()) : "—",
                appreciation != null && appreciation.signum() >= 0 ? "good" : appreciation != null ? "bad" : "muted"));
        lines.add(new ToneLineDto("Inventory holding cost", holdingCost != null ? "−" + Fmt.fmtMoney(holdingCost.doubleValue()) : "—", "bad"));
        lines.add(new ToneLineDto("Depreciation risk", depreciationRisk != null ? "−" + Fmt.fmtMoney(depreciationRisk.doubleValue()) : "—", "bad"));
        lines.add(new ToneLineDto("Demand uncertainty",
                demandUncertainty + (uncertaintyCost != null ? " (−" + Fmt.fmtMoney(uncertaintyCost.doubleValue()) + ")" : ""),
                demandUncertainty.equals("Low") ? "good" : "bad"));
        lines.add(new ToneLineDto("Net expected benefit", net != null ? money(net.doubleValue()) + " a unit" : "per unit only", net != null && net.signum() >= 0 ? "good" : net != null ? "bad" : "muted"));

        return new HoldDecisionDto(hold ? "hold" : "sell", days, bd(recommended), priceLater, appreciation,
                holdingCost, depreciationRisk, demandUncertainty, uncertaintyCost, net, netTotal, reason, lines);
    }

    private static String money(double n) {
        return (n < 0 ? "−" : "") + Fmt.fmtMoney(Math.abs(n));
    }

    // -- Liquidation signal ---------------------------------------------------------------------

    public LiquidationDto liquidationSignal(SellIntelDto intel) {
        BigDecimal driftPct90 = intel.forecast() != null ? intel.forecast().driftPct90() : null;
        BigDecimal weeksOfCover = intel.weeksOfCover();
        boolean falling = driftPct90 != null && driftPct90.doubleValue() < 0.5;
        boolean weak = "Low demand".equals(intel.demandLabel());
        boolean heavy = weeksOfCover != null && weeksOfCover.doubleValue() > 14;
        boolean active = intel.priceable() && weak && falling && heavy && intel.inventoryUnits() != null;
        int inventoryUnits = intel.inventoryUnits() == null ? 0 : intel.inventoryUnits();
        double valueNow = intel.currentPrice() == null ? 0 : round2(inventoryUnits * intel.currentPrice().doubleValue());
        double valueIn60 = driftPct90 == null ? valueNow : round2(valueNow * (1 + (driftPct90.doubleValue() * 0.72) / 100) * 0.97);
        double erosion = round2(valueNow - valueIn60);
        int discountPct = !active ? 0
                : (int) Math.round(clamp(8 + (weeksOfCover.doubleValue() - 14) * 0.8, 8, 22));
        String reason = active
                ? "Demand is falling, the market is " + (driftPct90.doubleValue() < 0 ? "softening" : "flat") + ", and this "
                        + "branch holds " + Fmt.fixed(weeksOfCover.doubleValue(), 0) + " weeks of stock. Every month of waiting "
                        + "erodes value; a " + discountPct + "% discount moves it while the price is still here."
                : "";
        return new LiquidationDto(active, inventoryUnits, bd(valueNow), bd(valueIn60), bd(erosion),
                discountPct, reason);
    }

    // -- Guardrails -------------------------------------------------------------------------------

    public GuardrailCheckDto applyGuardrails(SellIntelDto intel, GuardrailValues g) {
        if (intel.recommended() == null) {
            return new GuardrailCheckDto(false, null, null, null, "", "");
        }
        double marketPrice = intel.marketPrice() != null ? intel.marketPrice().doubleValue()
                : intel.recommended().doubleValue();
        double recommended = intel.recommended().doubleValue();
        double cost = d(intel.cost());
        double cap = round2(marketPrice * (1 + g.maxMarketDeviationPct() / 100));
        double floor = intel.cost() != null ? round2(cost / (1 - g.minMarginPct() / 100)) : Double.NEGATIVE_INFINITY;
        double finalPrice = recommended;
        String rule = "";
        boolean hasLimit = false;
        double limit = 0;
        if (finalPrice > cap) {
            finalPrice = cap;
            rule = "Maximum " + Fmt.jsNum(g.maxMarketDeviationPct()) + "% above market";
            limit = cap;
            hasLimit = true;
        }
        if (intel.cost() != null && finalPrice < floor) {
            finalPrice = floor;
            rule = "Minimum " + Fmt.jsNum(g.minMarginPct()) + "% margin";
            limit = floor;
            hasLimit = true;
        }
        finalPrice = round2(finalPrice);
        boolean adjusted = Math.abs(finalPrice - recommended) > 0.005;
        String reason = adjusted ? "Organisation pricing policy: " + rule.toLowerCase(Locale.ROOT) + "." : "";
        return new GuardrailCheckDto(adjusted, bd(recommended), bd(finalPrice), hasLimit ? bd(limit) : null, rule, reason);
    }

    // -- Decision score -----------------------------------------------------------------------------

    public SellDecisionScoreDto sellDecisionScore(SellIntelDto intel, String storeCode) {
        Window w12 = Window.trailingMonths(clock.today(), 12);
        Adoption adoption = deals.adoption("sell", w12.from(), w12.to(), storeCode);
        BigDecimal followRate = adoption.followRatePct().map(BigDecimal::valueOf).orElse(null);
        boolean willingnessAvailable = adoption.total() >= 3 && followRate != null;

        record W(String label, double weight, Integer value) {
        }
        List<W> raw = new ArrayList<>();
        Integer margin = intel.expectedMarginPct() != null
                ? (int) Math.round(clamp((intel.expectedMarginPct().doubleValue() / 40) * 100, 20, 100)) : null;
        raw.add(new W("Margin", 0.3, margin));
        Integer demand = "High demand".equals(intel.demandLabel()) ? 92
                : "Low demand".equals(intel.demandLabel()) ? 52 : intel.demandLabel() != null ? 76 : null;
        raw.add(new W("Demand", 0.25, demand));
        Integer market = null;
        if (intel.recommended() != null && intel.marketPrice() != null && intel.marketPrice().signum() != 0) {
            double gap = Math.abs(intel.recommended().doubleValue() - intel.marketPrice().doubleValue())
                    / Math.max(1, intel.marketPrice().doubleValue());
            market = (int) Math.round(clamp(100 - gap * 100 * 5, 30, 100));
        }
        raw.add(new W("Market position", 0.25, market));
        Integer willingness = willingnessAvailable
                ? (int) Math.round(clamp(followRate.doubleValue() + Math.min(12, intel.competitorCount() * 2) - 4, 30, 100))
                : null;
        raw.add(new W("Customer willingness", 0.2, willingness));

        double weightSum = raw.stream().filter(w -> w.value() != null).mapToDouble(W::weight).sum();
        double total = 0;
        List<ScorePartDto> parts = new ArrayList<>();
        for (W w : raw) {
            if (w.value() == null) {
                continue;
            }
            parts.add(new ScorePartDto(w.label(), w.value()));
            if (weightSum > 0) {
                total += (w.weight() / weightSum) * w.value();
            }
        }
        String risk = intel.confidence() >= 85 ? "Low" : intel.confidence() >= 70 ? "Medium" : "High";
        return new SellDecisionScoreDto((int) Math.round(total), parts, risk);
    }

    // -- What if (sell) ----------------------------------------------------------------------------

    private static String money0(double n) {
        return (n < 0 ? "−" : "+") + Fmt.fmtMoney(Math.abs(n), 0);
    }

    public SellWhatIfDto sellWhatIf(SellIntelDto intel, String scenario, String commodityKey) {
        return switch (scenario) {
            case "price-up-5" -> {
                ScenarioResultDto r = SellEngine.runScenario(intel, 5, d(intel.recommended()));
                double revenueDelta = d(r.revenueDelta());
                double marginDeltaPts = d(r.marginDeltaPts());
                double demandDeltaPct = d(r.demandDeltaPct());
                double profitDelta = d(r.profitDelta());
                List<ToneLineDto> rows = List.of(
                        new ToneLineDto("Revenue", money0(revenueDelta), revenueDelta >= 0 ? "good" : "bad"),
                        new ToneLineDto("Margin", (marginDeltaPts >= 0 ? "+" : "−") + Fmt.fixed(Math.abs(marginDeltaPts), 1) + " pts", "good"),
                        new ToneLineDto("Demand", Fmt.fixed(demandDeltaPct, 1) + "%", "bad"),
                        new ToneLineDto("Profit", money0(profitDelta), profitDelta >= 0 ? "good" : "bad"));
                String note = profitDelta >= 0
                        ? "Profit still rises: this line is not very price-sensitive."
                        : "Volume falls faster than margin rises.";
                yield new SellWhatIfDto("Raise the price 5% above the recommendation", rows, note);
            }
            case "demand-down-10" -> {
                int units = (int) Math.round(intel.annualUnits() * 0.9);
                double recommended = d(intel.recommended());
                double cost = d(intel.cost());
                double profit = (recommended - cost) * units;
                double base = (recommended - cost) * intel.annualUnits();
                double weeksOfCover = d(intel.weeksOfCover());
                List<ToneLineDto> rows = List.of(
                        new ToneLineDto("Units a year", Fmt.groupInt(units), "bad"),
                        new ToneLineDto("Revenue", money0(-recommended * intel.annualUnits() * 0.1), "bad"),
                        new ToneLineDto("Margin", (intel.expectedMarginPct() != null ? Fmt.fixed(intel.expectedMarginPct().doubleValue(), 1) : "—") + "% (unchanged)", "muted"),
                        new ToneLineDto("Profit", money0(profit - base), "bad"),
                        new ToneLineDto("Weeks of cover", intel.weeksOfCover() != null ? Fmt.fixed(weeksOfCover / 0.9, 1) + " wks" : "—", weeksOfCover / 0.9 > 14 ? "bad" : "muted"));
                yield new SellWhatIfDto("Demand falls 10%", rows,
                        "The loss is volume, not margin. Hold the price; if cover climbs past 14 weeks, the fast-"
                                + "movement bulk strategy is the lever.");
            }
            case "competitor-cut-5" -> {
                double marketPrice = intel.marketPrice() != null ? intel.marketPrice().doubleValue() : d(intel.recommended());
                double recommended = d(intel.recommended());
                double marginFloor = d(intel.marginFloor());
                double elasticityD = intel.elasticity() != null ? intel.elasticity().doubleValue() : -1.2;
                double cost = d(intel.cost());
                double newMarket = round2(marketPrice * 0.95);
                double follow = round2(Math.max(marginFloor, recommended * (1 - 0.05 * 0.7)));
                int holdUnits = (int) Math.round(intel.annualUnits() * (1 - Math.abs(elasticityD) * 0.035));
                double followProfit = (follow - cost) * intel.annualUnits();
                double holdProfit = (recommended - cost) * holdUnits;
                double base = (recommended - cost) * intel.annualUnits();
                boolean better = followProfit >= holdProfit;
                List<ToneLineDto> rows = List.of(
                        new ToneLineDto("New market benchmark", Fmt.fmtMoney(newMarket), "bad"),
                        new ToneLineDto("If you follow (70% of the cut)",
                                Fmt.fmtMoney(follow) + " · profit " + money0(followProfit - base), better ? "good" : "bad"),
                        new ToneLineDto("If you hold price",
                                "volume −" + Fmt.fixed(Math.abs(elasticityD) * 3.5, 1) + "% · profit " + money0(holdProfit - base),
                                !better ? "good" : "bad"),
                        new ToneLineDto("Better response", better ? "Follow part of the cut" : "Hold the price", "good"));
                String note = better
                        ? "This line is price-sensitive enough that matching most of the cut protects more profit than holding."
                        : "Customers here are not sensitive enough for the cut to take much volume. Hold, and watch the next month.";
                yield new SellWhatIfDto("A competitor cuts price 5%", rows, note);
            }
            case "hold-30" -> {
                HoldDecisionDto h = holdVsSell(intel, 30, commodityKey);
                yield new SellWhatIfDto("Hold the inventory for 30 days", h.lines().subList(1, h.lines().size()), h.reason());
            }
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
        };
    }
}
