package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.seed.Seeded;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.AccuracyDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ForecastModelDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ForecastPointDto;
import com.aatlas.sell.internal.dto.PricingDtos.CalcStepDto;
import com.aatlas.sell.internal.dto.PricingDtos.FactorWeightDto;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** Port of {@code src/lib/platform/forecast.ts}: {@code getForecastModel}. */
@Component
public class ForecastEngine {

    private record Horizon(String key, String label, String span, String method) {
    }

    private static final List<Horizon> HORIZONS = List.of(
            new Horizon("sensing", "Sensing", "0–4 weeks",
                    "Gradient-boosted and sequence models on high-frequency signals"),
            new Horizon("operational", "Operational", "1–6 months",
                    "Hierarchical ML ensembles with causal covariates"),
            new Horizon("tactical", "Tactical", "6–18 months",
                    "Structural time series with exogenous drivers"),
            new Horizon("strategic", "Strategic", "18–60 months", "Scenario-driven long-range models"));

    private final CatalogGateway catalog;
    private final PricingEngine pricing;

    public ForecastEngine(CatalogGateway catalog, PricingEngine pricing) {
        this.catalog = catalog;
        this.pricing = pricing;
    }

    private static Horizon horizonMeta(String horizon) {
        return HORIZONS.stream().filter(h -> h.key().equals(horizon)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown horizon: " + horizon));
    }

    private static List<String> periodLabels(String horizon, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(switch (horizon) {
                case "sensing" -> "Wk " + (i + 1);
                case "operational" -> "Mo " + (i + 1);
                case "tactical" -> "Mo " + ((i + 1) * 3);
                default -> "Yr " + (i + 1);
            });
        }
        return out;
    }

    public ForecastModelDto getForecastModel(String itemNumber, String storeId, String horizon) {
        ProductRef product = catalog.findProduct(itemNumber).orElse(null);
        String description = product != null ? product.description() : itemNumber;

        if (product == null || !product.hasSales()) {
            return new ForecastModelDto(itemNumber, description, storeId, horizon, false, List.of(), "flat",
                    bd(0), false, true, false, new AccuracyDto(bd(0), bd(0), bd(0), bd(0)), List.of(), List.of());
        }

        String key = "fc:" + itemNumber + ":" + storeId + ":" + horizon;
        PricingModel m = pricing.getPricingModel(itemNumber, storeId);

        double baseWeekly = Math.round(Seeded.randRange(key, "base", 6, 65));
        boolean intermittent = Seeded.rand(key, "intermittent") > 0.82;
        boolean coldStart = Seeded.rand(key, "cold") > 0.93;
        boolean structuralBreak = Seeded.rand(key, "break") > 0.87;

        double trendPct = round1(Seeded.randRange(key, "trend", -9, 15));
        String trend = trendPct > 2 ? "up" : trendPct < -2 ? "down" : "flat";

        int periodCount = horizon.equals("strategic") ? 5 : 6;
        List<String> labels = periodLabels(horizon, periodCount);
        double bandBase = switch (horizon) {
            case "sensing" -> 9;
            case "operational" -> 18;
            case "tactical" -> 30;
            default -> 46;
        };

        List<ForecastPointDto> points = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            double t = periodCount > 1 ? (double) i / (periodCount - 1) : 0;
            double spike = intermittent ? (Seeded.rand(key + ":" + i, "spike") > 0.62 ? 1.9 : 0.35) : 1;
            double level = baseWeekly * (1 + (trendPct / 100) * t) * spike;
            double spread = (bandBase + t * bandBase * 0.85) / 100;
            int p50 = (int) Math.max(0, Math.round(level));
            int p10 = (int) Math.max(0, Math.round(p50 * (1 - spread)));
            int p90 = (int) Math.round(p50 * (1 + spread));
            points.add(new ForecastPointDto(labels.get(i), p10, p50, p90));
        }

        double naiveMape = round1(Seeded.randRange(key, "naive", 24, 41));
        double modelMape = round1(naiveMape - Seeded.randRange(key, "modelgain", 7, 17));
        boolean humanHelped = Seeded.rand(key, "humangood") > 0.42;
        double humanMape = round1(humanHelped
                ? modelMape - Seeded.randRange(key, "humangain", 0.4, 3.6)
                : modelMape + Seeded.randRange(key, "humanloss", 0.4, 4.8));

        double w1 = Seeded.randRange(key, "w1", 18, 34);
        double w2 = Seeded.randRange(key, "w2", 8, 22);
        double w3 = Seeded.randRange(key, "w3", 14, 30);
        double sum = w1 + w2 + w3;
        double s1 = Math.round((w1 / sum) * 78 * 10) / 10.0;
        double s2 = Math.round((w2 / sum) * 78 * 10) / 10.0;
        double s3 = Math.round((w3 / sum) * 78 * 10) / 10.0;
        double momentum = round1(100 - (s1 + s2 + s3));

        List<FactorWeightDto> weights = List.of(
                new FactorWeightDto("Own price", bd(s1), "down",
                        "Read from the elasticity engine — a change in the commercial plan propagates here "
                                + "automatically."),
                new FactorWeightDto("Promotion calendar", bd(s2), "up"),
                new FactorWeightDto("Seasonality", bd(s3), trend.equals("down") ? "down" : "up"),
                new FactorWeightDto("Trend / momentum", bd(momentum), trend.equals("down") ? "down" : "up"));

        List<CalcStepDto> steps = new ArrayList<>();
        steps.add(new CalcStepDto("Trailing velocity at this store", Fmt.jsNum(baseWeekly) + "/wk",
                Fmt.groupInt(m.totalTransactions()) + " transactions behind this series", "step"));
        Horizon meta = horizonMeta(horizon);
        steps.add(new CalcStepDto("Horizon", meta.label(),
                meta.span() + " — " + meta.method().toLowerCase(java.util.Locale.ROOT), "step"));
        steps.add(new CalcStepDto("Price held at", Fmt.fmtMoney(m.currentPrice()),
                "Today’s price. Move it on the sell side and this curve redraws — nothing here treats price as fixed.",
                "step"));
        steps.add(new CalcStepDto("Trend applied", (trendPct >= 0 ? "+" : "") + Fmt.jsNum(trendPct) + "%",
                "Across the horizon, " + (trend.equals("flat") ? "essentially flat" : trend.equals("up") ? "growing" : "declining"),
                "step"));
        if (intermittent) {
            steps.add(new CalcStepDto("Intermittent demand flagged", null,
                    "Sparse, lumpy history — quantile loss is the objective here, not mean accuracy.", "step"));
        }
        if (structuralBreak) {
            steps.add(new CalcStepDto("Structural break flagged", null,
                    "A contract, competitor or tariff change in the recent history — surfaced, not smoothed away.",
                    "step"));
        }
        ForecastPointDto first = points.get(0);
        steps.add(new CalcStepDto("Median demand, " + labels.get(0), first.p50() + " units",
                "P10–P90 range " + first.p10() + "–" + first.p90(), "result"));

        return new ForecastModelDto(itemNumber, description, storeId, horizon, true, points, trend, bd(trendPct),
                intermittent, coldStart, structuralBreak,
                new AccuracyDto(bd(naiveMape), bd(modelMape), bd(humanMape), bd(round1(modelMape - humanMape))),
                weights, steps);
    }
}
