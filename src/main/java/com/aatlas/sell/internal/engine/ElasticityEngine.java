package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Round.round2;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.seed.Seeded;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.sell.internal.buy.BuySupplierGateway;
import com.aatlas.sell.internal.buy.SupplierRef;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.catalog.SeedOrder;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.CrossItemDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ElasticityModelDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ExperimentRowDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ResponsePointDto;
import com.aatlas.sell.internal.dto.PricingDtos.CalcStepDto;
import com.aatlas.sell.internal.dto.PricingDtos.FactorWeightDto;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Port of {@code src/lib/platform/elasticity.ts}: {@code getElasticityModel}, both sides. */
@Component
public class ElasticityEngine {

    private final CatalogGateway catalog;
    private final PricingEngine pricing;
    private final BuySupplierGateway suppliers;
    private final AatlasClock clock;

    public ElasticityEngine(CatalogGateway catalog, PricingEngine pricing, BuySupplierGateway suppliers,
            AatlasClock clock) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.suppliers = suppliers;
        this.clock = clock;
    }

    private String daysAgo(int days) {
        LocalDate date = clock.today().minusDays(days);
        return date.toString();
    }

    public ElasticityModelDto getElasticityModel(String itemNumber, String side, String counterpartyId) {
        ProductRef product = catalog.findProduct(itemNumber).orElse(null);
        String description = product != null ? product.description() : itemNumber;
        String key = "el:" + side + ":" + itemNumber + ":" + (blank(counterpartyId) ? "default" : counterpartyId);

        if (product == null || !product.hasSales()) {
            return new ElasticityModelDto(itemNumber, description, side, "—", false, bd(0), 0,
                    List.of(bd(0), bd(0)), "—", false, List.of(), List.of(), List.of(), List.of(), List.of());
        }

        int confidenceScore = (int) Math.round(Seeded.randRange(key, "conf", 38, 96));
        boolean usedFallback = confidenceScore < 55;
        double dataVolume = round1(Seeded.randRange(key, "vol", 20, 96));
        double priceVariation = round1(Seeded.randRange(key, "pv", 15, 92));
        double confounderControl = round1(Seeded.randRange(key, "cc", 30, 95));
        double backtestStability = round1(Seeded.randRange(key, "bt", 25, 97));

        double wSum = dataVolume + priceVariation + confounderControl + backtestStability;
        double[] raw = {dataVolume, priceVariation, confounderControl, backtestStability};
        String[] labels = {"Data volume observed", "Price variation in the window", "Confounder control",
                "Back-test stability"};
        double[] pct = new double[4];
        double sumFirst3 = 0;
        for (int i = 0; i < 3; i++) {
            pct[i] = round1((raw[i] / wSum) * 100);
            sumFirst3 += pct[i];
        }
        pct[3] = round1(100 - sumFirst3);
        List<FactorWeightDto> weights = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            weights.add(new FactorWeightDto(labels[i], bd(pct[i]), null, null));
        }

        if (side.equals("sell")) {
            return sellSide(itemNumber, product, description, key, counterpartyId, dataVolume, confounderControl,
                    backtestStability, usedFallback, weights, confidenceScore);
        }
        return buySide(itemNumber, description, key, counterpartyId, dataVolume, backtestStability, usedFallback,
                weights, confidenceScore);
    }

    private ElasticityModelDto sellSide(String itemNumber, ProductRef product, String description, String key,
            String counterpartyId, double dataVolume, double confounderControl, double backtestStability,
            boolean usedFallback, List<FactorWeightDto> weights, int confidenceScore) {
        String counterparty = !blank(counterpartyId) ? counterpartyId
                : (product.defaultStoreCode() != null ? product.defaultStoreCode() : "100349");
        PricingModel m = pricing.getPricingModel(itemNumber, counterparty);

        double coefficient = round2(-1 * Seeded.randRange(key, "coef", 0.35, 2.6));
        double bandLo = round2(coefficient - Seeded.randRange(key, "bandlo", 0.1, 0.45));
        double bandHi = round2(coefficient + Seeded.randRange(key, "bandhi", 0.1, 0.45));

        Map<String, ProductRef> byItem = catalog.sellableProducts().stream()
                .collect(Collectors.toMap(ProductRef::itemNumber, Function.identity(), (a, b) -> a));
        List<ProductRef> others = new ArrayList<>();
        for (String it : SeedOrder.ITEM_NUMBERS) {
            if (it.equals(itemNumber)) {
                continue;
            }
            ProductRef p = byItem.get(it);
            if (p != null) {
                others.add(p);
            }
        }
        List<CrossItemDto> cross = new ArrayList<>();
        for (int i = 0; i < 3 && !others.isEmpty(); i++) {
            ProductRef other = Seeded.pick(key + ":cross", String.valueOf(i), others);
            double effect = round1(Seeded.randRange(key + ":cross:" + i, "fx", -22, 8));
            cross.add(new CrossItemDto(other.description(), other.itemNumber(), bd(effect),
                    effect < 0 ? "cannibalisation" : "halo"));
        }

        List<ExperimentRowDto> experiments = new ArrayList<>();
        String[] expLabels = {"Geo-split price test", "Staged rollout, 3 branches"};
        for (int i = 0; i < 2; i++) {
            String expKey = key + ":exp:" + i;
            double predicted = round2(coefficient * Seeded.randRange(expKey, "p", 0.85, 1.1));
            double realised = round2(predicted * Seeded.randRange(expKey, "r", 0.8, 1.22));
            String date = daysAgo(Seeded.randInt(expKey, "date", 30, 300));
            experiments.add(new ExperimentRowDto(key + "-exp-" + i, expLabels[i], date, bd(predicted), bd(realised)));
        }

        List<CalcStepDto> steps = List.of(
                new CalcStepDto("Method",
                        usedFallback ? "Category prior, hierarchically pooled" : "Double machine learning",
                        usedFallback
                                ? "Too little of this item’s own price variation to trust an item-level estimate "
                                        + "— falls back to the category, visibly."
                                : "Controls for confounders on this item’s own price history",
                        "step"),
                new CalcStepDto("Granularity", "Item–store",
                        Fmt.groupInt(m.totalTransactions()) + " transactions in the window", "step"),
                new CalcStepDto("Data volume observed", Fmt.jsNum(dataVolume) + "/100", null, "step"),
                new CalcStepDto("Confounder control", Fmt.jsNum(confounderControl) + "/100",
                        "Demand shocks, seasonality and promo separated from the price effect", "step"),
                new CalcStepDto("Back-test stability", Fmt.jsNum(backtestStability) + "/100",
                        "Held out historical price moves, re-scored", "step"),
                new CalcStepDto("Own-price elasticity", Fmt.jsNum(coefficient),
                        "A 1% price rise moves quantity " + Fmt.fixed(coefficient, 2) + "% — "
                                + (Math.abs(coefficient) > 1 ? "elastic: revenue-sensitive to price"
                                        : "inelastic: revenue is more forgiving of price"),
                        "result"));

        List<ResponsePointDto> curve = new ArrayList<>();
        for (int pct = -20; pct <= 20; pct += 5) {
            double priceRatio = 1 + pct / 100.0;
            double qtyRatio = Math.pow(priceRatio, coefficient);
            curve.add(new ResponsePointDto(bd(pct), bd(round1((qtyRatio - 1) * 100))));
        }

        return new ElasticityModelDto(itemNumber, description, "sell", counterparty, true, bd(coefficient),
                confidenceScore, List.of(bd(bandLo), bd(bandHi)),
                usedFallback ? "Category prior" : "Item–store", usedFallback, curve, cross, experiments, weights,
                steps);
    }

    private ElasticityModelDto buySide(String itemNumber, String description, String key, String counterpartyId,
            double dataVolume, double backtestStability, boolean usedFallback, List<FactorWeightDto> weights,
            int confidenceScore) {
        List<SupplierRef> panel = suppliers.seededPanel();
        SupplierRef supplier;
        if (!blank(counterpartyId)) {
            supplier = panel.stream().filter(s -> s.supplierId().equals(counterpartyId)).findFirst()
                    .orElseGet(() -> Seeded.pick(key, "sup", panel));
        } else {
            supplier = Seeded.pick(key, "sup", panel);
        }

        double coefficient = round2(-1 * Seeded.randRange(key, "bcoef", 0.6, 4.2));
        double bandLo = round2(coefficient - Seeded.randRange(key, "bblo", 0.2, 0.8));
        double bandHi = round2(coefficient + Seeded.randRange(key, "bbhi", 0.2, 0.8));

        List<CrossItemDto> cross = List.of(
                new CrossItemDto("Contract tenor: 12 → 24 months", "Term response",
                        bd(round1(-1 * Seeded.randRange(key, "t1", 0.8, 3.4))), "sensitivity"),
                new CrossItemDto("Payment terms: Net 30 → Net 60", "Cost-of-capital adjusted",
                        bd(round1(-1 * Seeded.randRange(key, "t2", 0.3, 1.6))), "sensitivity"),
                new CrossItemDto("Order consolidation across branches", "Fewer, larger releases",
                        bd(round1(-1 * Seeded.randRange(key, "t3", 0.5, 2.8))), "sensitivity"));

        List<ExperimentRowDto> experiments = new ArrayList<>();
        String[] expLabels = {"Volume-tier renegotiation", "Consolidated award, 2 branches"};
        for (int i = 0; i < 2; i++) {
            String expKey = key + ":exp:" + i;
            double predicted = round2(coefficient * Seeded.randRange(expKey, "p", 0.85, 1.1));
            double realised = round2(predicted * Seeded.randRange(expKey, "r", 0.75, 1.25));
            String date = daysAgo(Seeded.randInt(expKey, "date", 40, 310));
            experiments.add(new ExperimentRowDto(key + "-exp-" + i, expLabels[i], date, bd(predicted), bd(realised)));
        }

        List<CalcStepDto> steps = List.of(
                new CalcStepDto("Method",
                        usedFallback ? "Category prior, hierarchically pooled" : "Hierarchical regression, isotonic fit",
                        usedFallback
                                ? "Not enough of this supplier’s own commitment history — falls back to the "
                                        + "category curve, visibly."
                                : "Fit to this supplier’s own PO and contract history, monotonicity enforced",
                        "step"),
                new CalcStepDto("Driver variables", null,
                        "Committed volume, order frequency, consolidation, exclusivity", "step"),
                new CalcStepDto("Data volume observed", Fmt.jsNum(dataVolume) + "/100", null, "step"),
                new CalcStepDto("Back-test stability", Fmt.jsNum(backtestStability) + "/100",
                        "Held-out renewals, re-scored", "step"),
                new CalcStepDto("Volume–price response", Fmt.jsNum(coefficient) + "%",
                        "Unit cost moves " + Fmt.fixed(coefficient, 2) + "% for every +10% committed volume with "
                                + supplier.name(),
                        "result"));

        List<ResponsePointDto> curve = new ArrayList<>();
        for (int pct = -20; pct <= 60; pct += 10) {
            double volRatio = 1 + pct / 100.0;
            double costRatio = Math.pow(volRatio, coefficient / 10);
            curve.add(new ResponsePointDto(bd(pct), bd(round1((costRatio - 1) * 100))));
        }

        return new ElasticityModelDto(itemNumber, description, "buy", supplier.name(), true, bd(coefficient),
                confidenceScore, List.of(bd(bandLo), bd(bandHi)),
                usedFallback ? "Category prior" : "Supplier–item", usedFallback, curve, cross, experiments, weights,
                steps);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
