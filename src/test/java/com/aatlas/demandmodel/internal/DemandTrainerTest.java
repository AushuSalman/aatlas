package com.aatlas.demandmodel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tribuo.Model;
import org.tribuo.protos.core.ModelProto;
import org.tribuo.regression.Regressor;

/**
 * The hidden-answer test: sales are simulated from a known price sensitivity, the model never
 * sees that number, and it has to find it. This proves the method recovers a planted pattern;
 * it says nothing about any real market, which only real sales can.
 */
class DemandTrainerTest {

    private static final LocalDate START = LocalDate.of(2024, 9, 2); // a Monday
    private static final double PLANTED_ELASTICITY = -1.6;

    /** Ten items over two branches and two categories, 80 weeks, a new price every six weeks within ±20%. */
    static List<WeeklyGrid.SalesWeek> simulate(int weeks, long seed) {
        Random rnd = new Random(seed);
        List<WeeklyGrid.SalesWeek> sales = new ArrayList<>();
        for (int p = 0; p < 10; p++) {
            String item = "ITEM" + p;
            String store = p % 2 == 0 ? "S1" : "S2";
            String category = p < 5 ? "pipe" : "wire";
            double base = 8 + 6 * p;
            double p0 = 10 + 3 * p;
            double price = p0;
            for (int w = 0; w < weeks; w++) {
                if (w % 6 == 0) {
                    price = p0 * (0.8 + 0.4 * rnd.nextDouble());
                }
                double season = 1 + 0.2 * Math.sin(2 * Math.PI * w / 52.0);
                double mu = base * Math.pow(price / p0, PLANTED_ELASTICITY) * season;
                double units = Math.round(mu * Math.exp(0.12 * rnd.nextGaussian()));
                if (units > 0) {
                    sales.add(new WeeklyGrid.SalesWeek(item, store, category, START.plusWeeks(w), units, price,
                            price * 0.6));
                }
            }
        }
        return sales;
    }

    @Test
    @DisplayName("recovers a planted price sensitivity, beats the naive baseline, and survives a round trip through bytes")
    void hiddenAnswer() throws Exception {
        int weeks = 80;
        LocalDate last = START.plusWeeks(weeks - 1);
        WeeklyGrid.Grid grid = WeeklyGrid.build(simulate(weeks, 7), List.of(), last, 12);
        assertThat(grid.contexts()).hasSize(10);

        DemandTrainer.Result r = DemandTrainer.train(grid, DemandTrainer.Settings.defaults().withTrees(60));

        double[] elasticities = r.evals().values().stream().mapToDouble(DemandTrainer.PairEval::elasticity).toArray();
        double median = WeeklyGrid.median(elasticities);
        assertThat(median).as("median probed elasticity across pairs, planted %s", PLANTED_ELASTICITY)
                .isBetween(-2.6, -0.7);
        long rightSign = r.evals().values().stream().filter(e -> e.elasticity() < 0).count();
        assertThat(rightSign).as("pairs with a downward price response").isGreaterThanOrEqualTo(8);
        long beats = r.evals().values().stream().filter(DemandTrainer.PairEval::beats).count();
        assertThat(beats).as("pairs where the model beat the trailing-mean baseline").isGreaterThanOrEqualTo(3);
        // Every forecaster is scored on every pair over two back-tests without seeing those weeks.
        assertThat(r.evals().values()).allSatisfy(e -> {
            assertThat(e.horizonError()).isBetween(0.0, 1.5);
            assertThat(e.horizonBaseline()).isGreaterThanOrEqualTo(0.0);
            assertThat(e.horizonUnits()).isGreaterThan(0);
            assertThat(e.trailingUnits()).isGreaterThan(0);
            assertThat(e.methods()).containsOnlyKeys(Smoothers.FOREST, Smoothers.SES, Smoothers.SBA, Smoothers.TSB);
            assertThat(e.methods().values()).allSatisfy(m -> {
                assertThat(m.earlier().units()).isGreaterThan(0);
                assertThat(m.later().units()).isGreaterThan(0);
            });
            assertThat(e.forecastNote()).isNotBlank();
            // In use exactly where a forecaster won twice, and then it is that forecaster's figure.
            assertThat(e.forecastUsable()).isEqualTo(DemandTrainer.pick(e.methods()).proven() != null);
            if (e.forecastUsable()) {
                assertThat(e.forecastMethod()).isEqualTo(DemandTrainer.pick(e.methods()).proven());
                assertThat(e.horizonUnits()).isEqualTo(e.methods().get(e.forecastMethod()).nextUnits());
                assertThat(e.horizonError()).isLessThan(e.horizonBaseline());
            }
        });
        // Prices here move demand by a third every six weeks: the forest sees the price, the smoothers cannot.
        long forestWins = r.evals().values().stream()
                .filter(e -> e.forecastUsable() && Smoothers.FOREST.equals(e.forecastMethod())).count();
        assertThat(forestWins).as("pairs where the forest's four-week forecast won twice").isGreaterThanOrEqualTo(3);
        assertThat(r.pooled().get(Smoothers.FOREST)[1].errorShare())
                .as("the forest's pooled miss against the recent average's, later back-test")
                .isLessThan(r.pooled().get(Smoothers.FOREST)[1].naiveShare());
        assertThat(r.evals().values()).allSatisfy(e -> {
            assertThat(e.holdoutUnits()).isGreaterThan(DemandTrainer.MIN_HOLDOUT_UNITS);
            assertThat(e.errorModel()).isBetween(0.0, 1.0);
            assertThat(e.forecastNext()).isGreaterThan(0);
        });

        byte[] bytes = r.response().serialize().toByteArray();
        @SuppressWarnings("unchecked")
        Model<Regressor> back = (Model<Regressor>) Model.deserialize(ModelProto.parseFrom(bytes));
        WeeklyGrid.Context c = grid.contexts().values().iterator().next();
        Features.Input in = Features.at(c, c.lastPrice(), true, r.season(), false);
        assertThat(r.season()).as("80 weeks is enough history for the season features").isTrue();
        assertThat(DemandTrainer.trainedWithSeason(back)).isTrue();
        assertThat(DemandTrainer.trainedWithSeason(r.forecast())).isTrue();
        assertThat(DemandTrainer.predictUnits(back, in)).isEqualTo(DemandTrainer.predictUnits(r.response(), in), within(1e-9));
    }

    @Test
    @DisplayName("a dearer price forecasts fewer units than a cheaper one, for a pair the model found usable")
    void forecastsFallWithPrice() {
        int weeks = 80;
        WeeklyGrid.Grid grid = WeeklyGrid.build(simulate(weeks, 11), List.of(), START.plusWeeks(weeks - 1), 12);
        DemandTrainer.Result r = DemandTrainer.train(grid, DemandTrainer.Settings.defaults().withTrees(60));
        String pair = r.evals().entrySet().stream().filter(e -> e.getValue().usable()).map(e -> e.getKey()).findFirst()
                .orElseThrow();
        WeeklyGrid.Context c = grid.contexts().get(pair);
        double cheap = DemandTrainer.settledUnits(r.response(), c, c.lastPrice() * 0.85, true, r.season());
        double dear = DemandTrainer.settledUnits(r.response(), c, c.lastPrice() * 1.15, true, r.season());
        assertThat(cheap).isGreaterThan(dear);
    }

    @Test
    @DisplayName("an outside forecaster joins the contest on what came before each forecast, and is left out when it does not answer")
    void outsideForecaster() {
        int weeks = 80;
        WeeklyGrid.Grid grid = WeeklyGrid.build(simulate(weeks, 7), List.of(), START.plusWeeks(weeks - 1), 12);
        DemandTrainer.Settings settings = DemandTrainer.Settings.defaults().withTrees(20);
        List<double[]> asked = new ArrayList<>();
        // Answers with the mean of the last four weeks it was shown.
        DemandTrainer.Outside lastFour = (series, horizon) -> {
            asked.addAll(series);
            return series.stream()
                    .mapToDouble(y -> java.util.Arrays.stream(y, Math.max(0, y.length - 4), y.length).average().orElse(0))
                    .toArray();
        };
        DemandTrainer.Result r = DemandTrainer.train(grid, settings, DemandTrainer.FOREST, lastFour);

        // Ten pairs, each asked from the start of four back-test blocks and from the end of its series. The
        // first week of the upload is dropped, so a pair has 79 weeks.
        assertThat(asked).hasSize(50);
        assertThat(asked.stream().mapToInt(y -> y.length).distinct().sorted().toArray())
                .containsExactly(63, 67, 71, 75, 79);
        assertThat(r.evals().values()).allSatisfy(e -> {
            assertThat(e.methods()).containsKey(Smoothers.CHRONOS);
            DemandTrainer.MethodEval ev = e.methods().get(Smoothers.CHRONOS);
            assertThat(ev.earlier().units()).isEqualTo(e.methods().get(Smoothers.SES).earlier().units());
            assertThat(ev.later().naive()).isEqualTo(e.methods().get(Smoothers.SES).later().naive());
            assertThat(ev.nextUnits()).isGreaterThan(0);
        });
        assertThat(Smoothers.label(Smoothers.CHRONOS)).isEqualTo("Chronos");

        // No answer, or the wrong number of answers: the contest runs with the four it has.
        for (DemandTrainer.Outside broken : List.<DemandTrainer.Outside>of((series, horizon) -> null,
                (series, horizon) -> new double[3])) {
            DemandTrainer.Result without = DemandTrainer.train(grid, settings, DemandTrainer.FOREST, broken);
            assertThat(without.evals().values())
                    .allSatisfy(e -> assertThat(e.methods()).doesNotContainKey(Smoothers.CHRONOS));
        }
    }

    private static DemandTrainer.MethodEval scored(double earlierMiss, double laterMiss) {
        // Forty units a back-test, the recent average missing by half of them each time.
        return new DemandTrainer.MethodEval(new DemandTrainer.Score(10, 10 * earlierMiss, 5),
                new DemandTrainer.Score(10, 10 * laterMiss, 5), 12);
    }

    @Test
    @DisplayName("a forecaster is used only when it beat the recent average twice in a row; of several, the closest")
    void pickedOnlyWhenProvenTwice() {
        java.util.Map<String, DemandTrainer.MethodEval> methods = new java.util.LinkedHashMap<>();
        methods.put(Smoothers.FOREST, scored(0.30, 0.60));   // won, then lost
        methods.put(Smoothers.SES, scored(0.49, 0.30));      // inside the margin, then won
        methods.put(Smoothers.SBA, scored(0.40, 0.40));      // won twice
        methods.put(Smoothers.TSB, scored(0.35, 0.42));      // won twice, and closer
        DemandTrainer.Pick pick = DemandTrainer.pick(methods);
        assertThat(pick.proven()).isEqualTo(Smoothers.TSB);
        assertThat(pick.closest()).isEqualTo(Smoothers.TSB);

        // Nobody won twice: nothing is used, and the closest is only reported.
        methods.remove(Smoothers.SBA);
        methods.remove(Smoothers.TSB);
        pick = DemandTrainer.pick(methods);
        assertThat(pick.proven()).isNull();
        assertThat(pick.closest()).isEqualTo(Smoothers.SES);

        // Two wins on too few units prove nothing.
        java.util.Map<String, DemandTrainer.MethodEval> thin = new java.util.LinkedHashMap<>();
        thin.put(Smoothers.SBA, new DemandTrainer.MethodEval(new DemandTrainer.Score(1, 0.1, 0.9),
                new DemandTrainer.Score(1, 0.1, 0.9), 0.3));
        assertThat(DemandTrainer.pick(thin).proven()).isNull();
    }

    @Test
    @DisplayName("a model chosen in Settings is used wherever it has a forecast, and the note says whether it is proven")
    void chosenModel() {
        java.util.Map<String, DemandTrainer.MethodEval> methods = new java.util.LinkedHashMap<>();
        methods.put(Smoothers.FOREST, scored(0.30, 0.60));   // won, then lost
        methods.put(Smoothers.SBA, scored(0.40, 0.40));      // won twice

        DemandTrainer.Verdict auto = DemandTrainer.verdict(methods, DemandTrainer.AUTO);
        assertThat(auto.method()).isEqualTo(Smoothers.SBA);
        assertThat(auto.usable()).isTrue();
        assertThat(auto.proven()).isTrue();
        assertThat(auto.errorShare()).isEqualTo(0.40, within(1e-9));
        assertThat(auto.naiveShare()).isEqualTo(0.50, within(1e-9));

        DemandTrainer.Verdict forest = DemandTrainer.verdict(methods, Smoothers.FOREST);
        assertThat(forest.method()).isEqualTo(Smoothers.FOREST);
        assertThat(forest.usable()).as("chosen, so used although it did not win twice").isTrue();
        assertThat(forest.proven()).isFalse();
        assertThat(forest.nextUnits()).isEqualTo(12);
        assertThat(forest.note()).contains("Random forest").contains("chosen in Settings").contains("has not beaten");

        DemandTrainer.Verdict sba = DemandTrainer.verdict(methods, Smoothers.SBA);
        assertThat(sba.usable()).isTrue();
        assertThat(sba.proven()).isTrue();
        assertThat(sba.note()).contains("Croston SBA").contains("It beat the recent average");

        // Chronos was not running at the last training run: nothing to use, and it says why.
        DemandTrainer.Verdict chronos = DemandTrainer.verdict(methods, Smoothers.CHRONOS);
        assertThat(chronos.usable()).isFalse();
        assertThat(chronos.note()).contains("Chronos").contains("no forecast for this item");

        // The models offered in Settings: automatic first, then every forecaster by its own key and name.
        assertThat(DemandModelService.MODEL_OPTIONS).extracting(com.aatlas.demandmodel.DemandModels.ModelOption::key)
                .containsExactly(DemandTrainer.AUTO, Smoothers.CHRONOS, Smoothers.FOREST, Smoothers.SBA, Smoothers.TSB,
                        Smoothers.SES);
        assertThat(DemandModelService.MODEL_OPTIONS).allSatisfy(o -> {
            assertThat(o.label()).isNotBlank();
            assertThat(o.hint()).isNotBlank();
        });
        assertThat(DemandModelService.MODEL_OPTIONS.stream().skip(1))
                .allSatisfy(o -> assertThat(o.label()).isEqualTo(Smoothers.label(o.key())));
    }

    @Test
    @DisplayName("too little history is refused rather than fitted")
    void refusesThinData() {
        List<WeeklyGrid.SalesWeek> few = simulate(16, 3).stream().filter(s -> s.item().equals("ITEM0")).toList();
        WeeklyGrid.Grid grid = WeeklyGrid.build(few, List.of(), START.plusWeeks(15), 12);
        assertThatThrownBy(() -> DemandTrainer.train(grid, DemandTrainer.Settings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needed");
    }
}
