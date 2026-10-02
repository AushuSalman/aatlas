package com.aatlas.supplymodel.internal;

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
 * The hidden-answer test: orders are simulated from suppliers with known habits, the model
 * never sees those habits, and it has to find them. It proves the method recovers a planted
 * pattern; only real purchase orders say anything about a real supplier.
 */
class DeliveryTrainerTest {

    private static final LocalDate START = LocalDate.of(2024, 1, 8);
    private static final String[] ITEMS = {"W100", "W200", "C300", "L400"};
    private static final String[] CATS = {"wire", "wire", "conduit", "lighting"};
    private static final String[] STORES = {"S1", "S2", "S3"};

    /**
     * Three suppliers over two years. RELIABLE lands on its promise give or take a day. SLOW slips
     * by three days plus a day per hundred units, almost always late. MIXED slips more in the
     * second half of the year (a planted season) and is late about half the time.
     */
    static List<OrderGrid.Order> simulate(int perSupplier, long seed) {
        Random rnd = new Random(seed);
        List<OrderGrid.Order> orders = new ArrayList<>();
        for (int i = 0; i < perSupplier; i++) {
            LocalDate date = START.plusDays(rnd.nextInt(730));
            int k = rnd.nextInt(ITEMS.length);
            double qty = 20 + rnd.nextInt(280);
            String store = STORES[rnd.nextInt(STORES.length)];
            double promised = 9;
            double slip = Math.round(0.6 * rnd.nextGaussian());
            orders.add(order(date, "RELIABLE", "USA", k, store, qty, promised, promised + slip));

            date = START.plusDays(rnd.nextInt(730));
            k = rnd.nextInt(ITEMS.length);
            qty = 20 + rnd.nextInt(280);
            promised = 30;
            slip = Math.round(3 + qty / 100.0 + 1.2 * rnd.nextGaussian());
            orders.add(order(date, "SLOW", "China", k, store, qty, promised, promised + slip));

            date = START.plusDays(rnd.nextInt(730));
            k = rnd.nextInt(ITEMS.length);
            qty = 20 + rnd.nextInt(280);
            promised = 14;
            double seasonal = date.getDayOfYear() > 182 ? 2.5 : 0;
            slip = Math.round(seasonal + rnd.nextGaussian());
            orders.add(order(date, "MIXED", "Mexico", k, store, qty, promised, promised + slip));
        }
        return orders;
    }

    private static OrderGrid.Order order(LocalDate date, String supplier, String country, int k, String store,
            double qty, double promised, double actual) {
        return new OrderGrid.Order(date, supplier, supplier + " Co", country, ITEMS[k], CATS[k], store, qty, promised,
                actual, actual > promised);
    }

    @Test
    @DisplayName("tells the slow supplier from the reliable one, beats the trailing record where there is a pattern, and survives bytes")
    void hiddenAnswer() throws Exception {
        OrderGrid.Grid grid = OrderGrid.build(simulate(90, 5));
        assertThat(grid.rows()).hasSize(270);

        DeliveryTrainer.Result r = DeliveryTrainer.train(grid, DeliveryTrainer.Settings.defaults().withTrees(60));

        assertThat(r.season()).as("two years of orders: season features on").isTrue();
        assertThat(r.holdoutRows()).isEqualTo(54);
        DeliveryTrainer.SupplierEval slow = r.evals().get("SLOW");
        DeliveryTrainer.SupplierEval reliable = r.evals().get("RELIABLE");
        DeliveryTrainer.SupplierEval mixed = r.evals().get("MIXED");
        assertThat(slow.predictedLateProb()).as("SLOW is almost always late").isGreaterThan(0.75);
        assertThat(reliable.predictedLateProb()).as("RELIABLE is rarely late").isLessThan(0.45);
        assertThat(slow.predictedLateProb() - reliable.predictedLateProb()).isGreaterThan(0.3);
        assertThat(slow.predictedSlip()).as("SLOW slips by days").isGreaterThan(2.0);
        assertThat(Math.abs(reliable.predictedSlip())).as("RELIABLE lands near its promise").isLessThan(1.0);
        assertThat(r.evals().values()).allSatisfy(e -> {
            assertThat(e.holdoutOrders()).isGreaterThanOrEqualTo(DeliveryTrainer.MIN_HOLDOUT);
            assertThat(e.brierModel()).isBetween(0.0, 0.5);
        });
        long usable = r.evals().values().stream().filter(DeliveryTrainer.SupplierEval::usable).count();
        assertThat(usable).as("a planted quantity or season effect is something the trailing record cannot see")
                .isGreaterThanOrEqualTo(1);
        assertThat(mixed.note()).isNotBlank();

        byte[] bytes = r.late().serialize().toByteArray();
        @SuppressWarnings("unchecked")
        Model<Regressor> back = (Model<Regressor>) Model.deserialize(ModelProto.parseFrom(bytes));
        OrderGrid.Context c = grid.contexts().get("SLOW");
        DeliveryFeatures.Input in = DeliveryFeatures.at(c, "wire", "S1", 150, 30, c.lastOrder(), r.season());
        assertThat(DeliveryTrainer.trainedWithSeason(back)).isTrue();
        assertThat(DeliveryTrainer.predict(back, in)).isEqualTo(DeliveryTrainer.predict(r.late(), in), within(1e-9));
    }

    @Test
    @DisplayName("a bigger order from the slow supplier is forecast to slip more")
    void quantityEffect() {
        OrderGrid.Grid grid = OrderGrid.build(simulate(90, 9));
        DeliveryTrainer.Result r = DeliveryTrainer.train(grid, DeliveryTrainer.Settings.defaults().withTrees(60));
        OrderGrid.Context c = grid.contexts().get("SLOW");
        double small = DeliveryTrainer.predict(r.slip(), DeliveryFeatures.at(c, "wire", "S1", 30, 30, c.lastOrder(), r.season()));
        double big = DeliveryTrainer.predict(r.slip(), DeliveryFeatures.at(c, "wire", "S1", 290, 30, c.lastOrder(), r.season()));
        assertThat(big).isGreaterThan(small);
    }

    @Test
    @DisplayName("too few orders are refused rather than fitted")
    void refusesThinData() {
        OrderGrid.Grid grid = OrderGrid.build(simulate(6, 3));
        assertThatThrownBy(() -> DeliveryTrainer.train(grid, DeliveryTrainer.Settings.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("needed");
    }
}
