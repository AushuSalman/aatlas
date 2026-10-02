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
    @DisplayName("too little history is refused rather than fitted")
    void refusesThinData() {
        List<WeeklyGrid.SalesWeek> few = simulate(16, 3).stream().filter(s -> s.item().equals("ITEM0")).toList();
        WeeklyGrid.Grid grid = WeeklyGrid.build(few, List.of(), START.plusWeeks(15), 12);
        assertThatThrownBy(() -> DemandTrainer.train(grid, DemandTrainer.Settings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needed");
    }
}
