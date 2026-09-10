package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.PricingEngine.marginPercent;
import static com.aatlas.sell.internal.engine.Round.clamp;
import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.CommodityTrend;
import com.aatlas.sell.internal.catalog.CatalogRefs.CustomerRef;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/intel/sell2.ts}, minus {@code allocateInventory} ({@link
 * AtpEngine}, a cross-track stand-in) and the deal-quote maths ({@link DealEngine}, ported
 * from {@code platform/deal.ts} since {@code POST /sell/quote} needs it and WAVE2-BRIEF's
 * file list for this track did not name it).
 */
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

    public Sell2Engine(CatalogGateway catalog) {
        this.catalog = catalog;
    }

    public CustomerProfileDto customerProfile(CustomerRef c) {
        String profile = c != null ? c.profile() : "repeat";
        int slaDays = c != null ? c.slaDays() : 7;
        ProfileMeta meta = PROFILES.getOrDefault(profile, PROFILES.get("repeat"));
        return new CustomerProfileDto(profile, slaDays, meta.label(), meta.fulfilment(), meta.traits());
    }

    // -- Speed premium ----------------------------------------------------------------------

    public SpeedPricingDto speedPricing(SellIntelDto intel, GuardrailValues g) {
        double demandPct = intel.demandPct().doubleValue();
        double demandBoost = demandPct > 0 ? 2.2 : demandPct < 0 ? -1 : 0.8;
        double rawPremium = round1(clamp(5.2 + demandBoost + (intel.confidence() - 75) / 25.0, 3, 14));
        double premiumPct = Math.min(rawPremium, g.maxSpeedPremiumPct());
        boolean cappedByPolicy = rawPremium > g.maxSpeedPremiumPct();
        double cost = intel.cost().doubleValue();
        double recommended = intel.recommended().doubleValue();
        double floor = cost / (1 - g.minMarginPct() / 100);
        double urgentPrice = round2(recommended * (1 + premiumPct / 100));
        double flexiblePrice = round2(Math.max(floor, recommended * (1 - 3.5 / 100)));

        List<CustomerRef> customers = catalog.allCustomers();

        SpeedTierDto urgent = tier(intel, "urgent", "Urgent", "Same-day or 48-hour fulfilment", urgentPrice, customers);
        SpeedTierDto standard = tier(intel, "standard", "Standard", "3–7 days", recommended, customers);
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
        double recommended = intel.recommended().doubleValue();
        List<String> names = customers.stream()
                .filter(c -> customerProfile(c).fulfilment().equals(key))
                .map(CustomerRef::name)
                .toList();
        return new SpeedTierDto(key, label, requirement, bd(price), bd(round1(((price - recommended) / recommended) * 100)),
                bd(round2(price - recommended)), bd(marginPercent(price, intel.cost().doubleValue())), names);
    }

    // -- Hold vs sell -------------------------------------------------------------------------

    public HoldDecisionDto holdVsSell(SellIntelDto intel, int days, String commodityKey) {
        double recommended = intel.recommended().doubleValue();
        double cost = intel.cost().doubleValue();
        double share = days / 90.0;
        double driftPct90 = intel.forecast().driftPct90().doubleValue();
        double priceLater = round2(recommended * (1 + (driftPct90 * share) / 100));
        double appreciation = round2(priceLater - recommended);
        double holdingCost = round2(cost * (0.22 / 12) * (days / 30.0) + cost * 0.004);
        CommodityTrend commodity = catalog.commodityTrend(commodityKey);
        double depreciationRisk = round2(cost * (0.006 + (commodity.pct90() < 0 ? Math.abs(commodity.pct90()) / 100 : 0) * 0.5));
        String demandLabel = intel.demandLabel();
        String demandUncertainty = demandLabel.equals("High demand") ? "Low" : demandLabel.equals("Low demand") ? "High" : "Medium";
        double uncertaintyCost = round2(Math.max(0, appreciation)
                * (demandUncertainty.equals("Low") ? 0.1 : demandUncertainty.equals("Medium") ? 0.3 : 0.55));
        double net = round2(appreciation - holdingCost - depreciationRisk - uncertaintyCost);
        boolean hold = net > 0.05 && !demandUncertainty.equals("High");

        String reason = hold
                ? "The forecast adds " + money(appreciation) + " a unit in " + days + " days. After "
                        + money(holdingCost) + " of holding cost, " + money(depreciationRisk)
                        + " of depreciation risk and a " + demandUncertainty.toLowerCase(Locale.ROOT)
                        + " demand discount, " + money(net) + " a unit is still left: "
                        + money(net * intel.inventoryUnits()) + " on the stock you hold."
                : appreciation <= 0
                        ? "The market is not expected to rise in " + days + " days. Holding only adds "
                                + money(holdingCost + depreciationRisk) + " a unit of cost. Sell now."
                        : "The forecast adds " + money(appreciation) + " a unit, but holding costs " + money(holdingCost)
                                + ", depreciation risk " + money(depreciationRisk) + " and "
                                + demandUncertainty.toLowerCase(Locale.ROOT) + " demand uncertainty eat it. Net "
                                + money(net) + ": sell now.";

        List<ToneLineDto> lines = List.of(
                new ToneLineDto("Selling price now", Fmt.fmtMoney(recommended), null),
                new ToneLineDto("Forecast in " + days + " days", Fmt.fmtMoney(priceLater), appreciation >= 0 ? "good" : "bad"),
                new ToneLineDto("Potential additional margin", money(appreciation), appreciation >= 0 ? "good" : "bad"),
                new ToneLineDto("Inventory holding cost", "−" + Fmt.fmtMoney(holdingCost), "bad"),
                new ToneLineDto("Depreciation risk", "−" + Fmt.fmtMoney(depreciationRisk), "bad"),
                new ToneLineDto("Demand uncertainty", demandUncertainty + " (−" + Fmt.fmtMoney(uncertaintyCost) + ")",
                        demandUncertainty.equals("Low") ? "good" : "bad"),
                new ToneLineDto("Net expected benefit", money(net) + " a unit", net >= 0 ? "good" : "bad"));

        return new HoldDecisionDto(hold ? "hold" : "sell", days, bd(recommended), bd(priceLater), bd(appreciation),
                bd(holdingCost), bd(depreciationRisk), demandUncertainty, bd(uncertaintyCost), bd(net),
                bd(round2(net * intel.inventoryUnits())), reason, lines);
    }

    private static String money(double n) {
        return (n < 0 ? "−" : "") + Fmt.fmtMoney(Math.abs(n));
    }

    // -- Liquidation signal ---------------------------------------------------------------------

    public LiquidationDto liquidationSignal(SellIntelDto intel) {
        double driftPct90 = intel.forecast().driftPct90().doubleValue();
        boolean falling = driftPct90 < 0.5;
        boolean weak = intel.demandLabel().equals("Low demand");
        double weeksOfCover = intel.weeksOfCover().doubleValue();
        boolean heavy = weeksOfCover > 14;
        boolean active = intel.priceable() && weak && falling && heavy;
        double valueNow = round2(intel.inventoryUnits() * intel.currentPrice().doubleValue());
        double valueIn60 = round2(valueNow * (1 + (driftPct90 * 0.72) / 100) * 0.97);
        double erosion = round2(valueNow - valueIn60);
        int discountPct = (int) Math.round(clamp(8 + (weeksOfCover - 14) * 0.8, 8, 22));
        String reason = active
                ? "Demand is falling, the market is " + (driftPct90 < 0 ? "softening" : "flat") + ", and this "
                        + "branch holds " + Fmt.fixed(weeksOfCover, 0) + " weeks of stock. Every month of waiting "
                        + "erodes value; a " + discountPct + "% discount moves it while the price is still here."
                : "";
        return new LiquidationDto(active, intel.inventoryUnits(), bd(valueNow), bd(valueIn60), bd(erosion),
                discountPct, reason);
    }

    // -- Guardrails -------------------------------------------------------------------------------

    public GuardrailCheckDto applyGuardrails(SellIntelDto intel, GuardrailValues g) {
        double marketPrice = intel.marketPrice().doubleValue();
        double recommended = intel.recommended().doubleValue();
        double cost = intel.cost().doubleValue();
        double cap = round2(marketPrice * (1 + g.maxMarketDeviationPct() / 100));
        double floor = round2(cost / (1 - g.minMarginPct() / 100));
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
        if (finalPrice < floor) {
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

    public SellDecisionScoreDto sellDecisionScore(SellIntelDto intel, double conversionPct) {
        double expectedMarginPct = intel.expectedMarginPct().doubleValue();
        int margin = (int) Math.round(clamp((expectedMarginPct / 40) * 100, 20, 100));
        int demand = intel.demandLabel().equals("High demand") ? 92 : intel.demandLabel().equals("Low demand") ? 52 : 76;
        double recommended = intel.recommended().doubleValue();
        double marketPrice = intel.marketPrice().doubleValue();
        double gap = Math.abs(recommended - marketPrice) / Math.max(1, marketPrice);
        int market = (int) Math.round(clamp(100 - gap * 100 * 5, 30, 100));
        int willingness = (int) Math.round(clamp(conversionPct + Math.min(12, intel.competitorCount() * 2) - 4, 30, 100));
        int total = (int) Math.round(0.3 * margin + 0.25 * demand + 0.25 * market + 0.2 * willingness);
        String risk = intel.confidence() >= 85 ? "Low" : intel.confidence() >= 70 ? "Medium" : "High";
        List<ScorePartDto> parts = List.of(
                new ScorePartDto("Margin", margin), new ScorePartDto("Demand", demand),
                new ScorePartDto("Market position", market), new ScorePartDto("Customer willingness", willingness));
        return new SellDecisionScoreDto(total, parts, risk);
    }

    // -- What if (sell) ----------------------------------------------------------------------------

    private static String money0(double n) {
        return (n < 0 ? "−" : "+") + Fmt.fmtMoney(Math.abs(n), 0);
    }

    public SellWhatIfDto sellWhatIf(SellIntelDto intel, String scenario, String commodityKey) {
        return switch (scenario) {
            case "price-up-5" -> {
                ScenarioResultDto r = SellEngine.runScenario(intel, 5, intel.recommended().doubleValue());
                double revenueDelta = r.revenueDelta().doubleValue();
                double marginDeltaPts = r.marginDeltaPts().doubleValue();
                double demandDeltaPct = r.demandDeltaPct().doubleValue();
                double profitDelta = r.profitDelta().doubleValue();
                List<ToneLineDto> rows = List.of(
                        new ToneLineDto("Revenue", money0(revenueDelta), revenueDelta >= 0 ? "good" : "bad"),
                        new ToneLineDto("Margin", (marginDeltaPts >= 0 ? "+" : "−") + Fmt.fixed(Math.abs(marginDeltaPts), 1) + " pts", "good"),
                        new ToneLineDto("Demand", Fmt.fixed(demandDeltaPct, 1) + "%", "bad"),
                        new ToneLineDto("Profit", money0(profitDelta), profitDelta >= 0 ? "good" : "bad"));
                String note = profitDelta >= 0
                        ? "Profit still rises: this line is not very price-sensitive. The recommendation stays "
                                + "conservative because of the competitor range."
                        : "Volume falls faster than margin rises. The recommendation is already near the top of "
                                + "what the market takes.";
                yield new SellWhatIfDto("Raise the price 5% above the recommendation", rows, note);
            }
            case "demand-down-10" -> {
                int units = (int) Math.round(intel.annualUnits() * 0.9);
                double recommended = intel.recommended().doubleValue();
                double cost = intel.cost().doubleValue();
                double profit = (recommended - cost) * units;
                double base = (recommended - cost) * intel.annualUnits();
                double weeksOfCover = intel.weeksOfCover().doubleValue();
                List<ToneLineDto> rows = List.of(
                        new ToneLineDto("Units a year", Fmt.groupInt(units), "bad"),
                        new ToneLineDto("Revenue", money0(-recommended * intel.annualUnits() * 0.1), "bad"),
                        new ToneLineDto("Margin", Fmt.fixed(intel.expectedMarginPct().doubleValue(), 1) + "% (unchanged)", "muted"),
                        new ToneLineDto("Profit", money0(profit - base), "bad"),
                        new ToneLineDto("Weeks of cover", Fmt.fixed(weeksOfCover / 0.9, 1) + " wks", weeksOfCover / 0.9 > 14 ? "bad" : "muted"));
                yield new SellWhatIfDto("Demand falls 10%", rows,
                        "The loss is volume, not margin. Hold the price; if cover climbs past 14 weeks, the fast-"
                                + "movement bulk strategy is the lever.");
            }
            case "competitor-cut-5" -> {
                double marketPrice = intel.marketPrice().doubleValue();
                double recommended = intel.recommended().doubleValue();
                double marginFloor = intel.marginFloor().doubleValue();
                double elasticityD = intel.elasticity().doubleValue();
                double cost = intel.cost().doubleValue();
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
                        ? "This line is price-sensitive enough that matching most of the cut protects more profit "
                                + "than holding."
                        : "Customers here are not sensitive enough for the cut to take much volume. Hold, and "
                                + "watch the next month.";
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
