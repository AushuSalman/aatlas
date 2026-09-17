package com.aatlas.sell.internal.engine;

import static com.aatlas.sell.internal.engine.Round.round1;
import static com.aatlas.sell.internal.engine.Wire.bd;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Catalogue.ProductRef;
import com.aatlas.history.Forecasts;
import com.aatlas.history.Forecasts.Forecast;
import com.aatlas.history.Forecasts.Point;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesHistory.MonthPoint;
import com.aatlas.history.SalesHistory.Seasonality;
import com.aatlas.sell.internal.catalog.CatalogGateway;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.AccuracyDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ForecastModelDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ForecastPointDto;
import com.aatlas.sell.internal.dto.PricingDtos.CalcStepDto;
import com.aatlas.sell.internal.dto.PricingDtos.FactorWeightDto;
import com.aatlas.sell.internal.engine.PricingTypes.PricingModel;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Port of {@code src/lib/platform/forecast.ts}: {@code getForecastModel}, now over {@code
 * history.Forecasts.seasonalNaive} (spec 3.4) - seasonal naive with a damped log-linear
 * trend, backtested for accuracy, over the real monthly unit series at this store. A cold
 * start (fewer than six months of the item's own history) scales the category's series
 * instead; an item with no months at all, or a category with nothing in its last three
 * months, cannot forecast at all and is {@code locked}.
 */
@Component
public class ForecastEngine {

    private record Horizon(String key, String label, String span, String method) {
    }

    private static final List<Horizon> HORIZONS = List.of(
            new Horizon("sensing", "Sensing", "0-6 weeks", "Seasonal-naive, weekly de-aggregated"),
            new Horizon("operational", "Operational", "1-6 months", "Seasonal-naive with a damped log-linear trend"),
            new Horizon("tactical", "Tactical", "3-18 months", "Three-month blocks of the same model"),
            new Horizon("strategic", "Strategic", "1-5 years", "Yearly sums, trend damped further each year"));

    private final CatalogGateway catalog;
    private final PricingEngine pricing;
    private final SalesHistory sales;
    private final AatlasClock clock;

    public ForecastEngine(CatalogGateway catalog, PricingEngine pricing, SalesHistory sales, AatlasClock clock) {
        this.catalog = catalog;
        this.pricing = pricing;
        this.sales = sales;
        this.clock = clock;
    }

    private static Horizon horizonMeta(String horizon) {
        return HORIZONS.stream().filter(h -> h.key().equals(horizon)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown horizon: " + horizon));
    }

    private static double[] toArray(List<MonthPoint> points) {
        double[] out = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            var u = points.get(i).units();
            out[i] = u == null ? 0 : u.doubleValue();
        }
        return out;
    }

    public ForecastModelDto getForecastModel(String itemNumber, String storeId, String horizon) {
        LocalDate today = clock.today();
        Optional<ProductRef> productOpt = catalog.findProduct(itemNumber);
        String description = productOpt.map(ProductRef::description).orElse(itemNumber);

        if (productOpt.isEmpty()) {
            return new ForecastModelDto(itemNumber, description, storeId, horizon, false, List.of(), "flat",
                    null, false, true, false, new AccuracyDto(null, null, null, null), List.of(), List.of());
        }
        ProductRef product = productOpt.get();
        PricingModel m = pricing.getPricingModel(itemNumber, storeId);
        java.util.UUID storeUuid = m.storeUuid();

        List<MonthPoint> series = sales.monthly(product.id(), storeUuid, 24, today);
        double[] itemUnits = toArray(series);
        boolean anyUnits = java.util.Arrays.stream(itemUnits).anyMatch(v -> v > 0);

        double[] usedSeries = itemUnits;
        boolean coldStartInput = false;
        if (!anyUnits || monthsSinceFirst(itemUnits) < Forecasts.COLD_START_MONTHS) {
            List<MonthPoint> catSeries = product.category() == null ? List.of()
                    : sales.monthlyCategory(product.category(), storeUuid, 24, today);
            double[] catUnits = toArray(catSeries);
            double[] scaled = Forecasts.coldStartSeries(itemUnits, catUnits);
            if (scaled == null) {
                return new ForecastModelDto(itemNumber, description, storeId, horizon, false, List.of(), "flat",
                        null, false, true, false, new AccuracyDto(null, null, null, null), List.of(), List.of());
            }
            usedSeries = scaled;
            coldStartInput = true;
        }

        Optional<Seasonality> seasonality = sales.seasonality(product.id(), today);
        double[] index = seasonality.map(Seasonality::index).orElse(null);
        int endMonth = today.getMonthValue();
        boolean defaultElasticity = SalesHistory.Elasticity.DEFAULT.equals(m.elasticityBasis());
        double beta = m.beta() == null ? -1.2 : m.beta().doubleValue();
        double r2 = m.betaR2() == null ? 0 : m.betaR2().doubleValue();

        Forecast forecast = Forecasts.seasonalNaive(usedSeries, endMonth, index, beta, r2, defaultElasticity);
        if (coldStartInput) {
            forecast = forecast.asColdStart();
        }

        List<Point> points = switch (horizon) {
            case "sensing" -> forecast.sensing();
            case "operational" -> forecast.operational();
            case "tactical" -> forecast.tactical();
            case "strategic" -> forecast.strategic();
            default -> throw new IllegalArgumentException("Unknown horizon: " + horizon);
        };
        List<ForecastPointDto> dtoPoints = points.stream()
                .map(p -> new ForecastPointDto(p.label(), (int) Math.max(0, Math.round(p.p10())),
                        (int) Math.max(0, Math.round(p.p50())), (int) Math.max(0, Math.round(p.p90()))))
                .toList();

        int horizonMonths = switch (horizon) {
            case "sensing" -> 1;
            case "operational" -> 6;
            case "tactical" -> 18;
            default -> 60;
        };
        double trendPct = round1(forecast.trendPct(horizonMonths));
        String trend = forecast.trend(horizonMonths);

        Horizon meta = horizonMeta(horizon);
        List<CalcStepDto> steps = new ArrayList<>();
        steps.add(new CalcStepDto("Level at origin", Fmt.jsNum(round1(forecast.level())) + "/mo",
                "Mean of the last three de-seasonalised months.", "step"));
        steps.add(new CalcStepDto("Horizon", meta.label(), meta.span() + " - " + meta.method(), "step"));
        steps.add(new CalcStepDto("Monthly growth", (forecast.growth() >= 0 ? "+" : "")
                + Fmt.fixed(forecast.growth() * 100, 2) + "%",
                "Log-linear trend over the last " + Math.min(forecast.months(), 12) + " months, clamped to ±8%.",
                "step"));
        if (forecast.seasonal()) {
            steps.add(new CalcStepDto("Seasonality", "Applied", "Twelve monthly indices from the item's own history.",
                    "step"));
        }
        if (forecast.intermittent()) {
            steps.add(new CalcStepDto("Intermittent demand flagged", null,
                    "Four or more zero months in the last twelve.", "step"));
        }
        if (forecast.structuralBreak()) {
            steps.add(new CalcStepDto("Structural break flagged", null,
                    "The last three months differ from the prior nine by more than two standard deviations.",
                    "step"));
        }
        if (!dtoPoints.isEmpty()) {
            ForecastPointDto first = dtoPoints.get(0);
            steps.add(new CalcStepDto("Median demand, " + first.period(), first.p50() + " units",
                    "P10-P90 range " + first.p10() + "-" + first.p90(), "result"));
        }

        Forecasts.Weights w = forecast.weights();
        List<FactorWeightDto> weights = List.of(
                new FactorWeightDto("Own price", bd(round1(w.ownPrice())), "down",
                        "Read from the elasticity engine - a change in the commercial plan propagates here automatically."),
                new FactorWeightDto("Seasonality", bd(round1(w.seasonality())), trend.equals("down") ? "down" : "up"),
                new FactorWeightDto("Trend / momentum", bd(round1(w.trend())), trend.equals("down") ? "down" : "up"),
                new FactorWeightDto("Unexplained", bd(round1(w.unexplained())), "up"));

        AccuracyDto accuracy = forecast.accuracy().available()
                ? new AccuracyDto(bd(round1(forecast.accuracy().naiveMape())), bd(round1(forecast.accuracy().modelMape())), null, null)
                : new AccuracyDto(null, null, null, null);

        return new ForecastModelDto(itemNumber, description, storeId, horizon, true, dtoPoints, trend, bd(trendPct),
                forecast.intermittent(), forecast.coldStart(), forecast.structuralBreak(), accuracy, weights, steps);
    }

    private static int monthsSinceFirst(double[] series) {
        for (int i = 0; i < series.length; i++) {
            if (series[i] > 0) {
                return series.length - i;
            }
        }
        return 0;
    }
}
