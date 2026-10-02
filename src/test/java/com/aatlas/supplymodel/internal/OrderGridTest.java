package com.aatlas.supplymodel.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderGridTest {

    private static final LocalDate D0 = LocalDate.of(2026, 3, 2);

    private static OrderGrid.Order order(LocalDate date, String supplier, String item, double qty, double promised,
            double actual) {
        return new OrderGrid.Order(date, supplier, "Supplier " + supplier, "USA", item, "wire", "S1", qty, promised,
                actual, actual > promised);
    }

    @Test
    @DisplayName("each row carries the supplier's record over the orders before it, never the order itself")
    void trailingRecord() {
        List<OrderGrid.Order> orders = List.of(
                order(D0, "A", "X", 100, 10, 10),
                order(D0.plusDays(3), "A", "Y", 50, 10, 14),
                order(D0.plusDays(9), "A", "X", 80, 10, 9),
                order(D0.plusDays(12), "A", "Z", 20, 12, 15),
                order(D0.plusDays(5), "B", "X", 10, 20, 20));

        OrderGrid.Grid g = OrderGrid.build(orders);

        assertThat(g.rows()).hasSize(5);
        assertThat(g.firstOrder()).isEqualTo(D0);
        assertThat(g.lastOrder()).isEqualTo(D0.plusDays(12));
        OrderGrid.Row first = g.rows().get(0);
        assertThat(first.prevOrders()).isZero();
        assertThat(first.hasPrev()).isFalse();
        // The fourth order of A (D0+12) sees the three before it: late 1 of 3, slips 0, +4, -1.
        OrderGrid.Row fourth = g.rows().stream().filter(r -> r.order().item().equals("Z")).findFirst().orElseThrow();
        assertThat(fourth.prevOrders()).isEqualTo(3);
        assertThat(fourth.hasPrev()).isTrue();
        assertThat(fourth.prevLateRate()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(fourth.prevMeanSlip()).isCloseTo(1.0, within(1e-9));
        assertThat(fourth.prevMeanActual()).isCloseTo(11.0, within(1e-9));
        assertThat(fourth.order().slip()).isEqualTo(3.0);
        assertThat(fourth.order().late()).isTrue();

        OrderGrid.Context a = g.contexts().get("A");
        assertThat(a.orders()).isEqualTo(4);
        assertThat(a.late()).isEqualTo(2);
        assertThat(a.lateRate()).isEqualTo(0.5);
        assertThat(a.meanSlip()).isCloseTo(1.5, within(1e-9));
        assertThat(a.meanPromised()).isCloseTo(10.5, within(1e-9));
        assertThat(a.meanQty()).isCloseTo(62.5, within(1e-9));
        assertThat(a.promisedByItem()).containsEntry("X", 10.0).containsEntry("Z", 12.0);
        assertThat(a.lastOrder()).isEqualTo(D0.plusDays(12));
        assertThat(g.contexts().get("B").orders()).isEqualTo(1);
    }

    @Test
    @DisplayName("features: the record is used only once there are enough previous orders; one-hots name supplier, country, category, branch")
    void features() {
        DeliveryFeatures.Vector v = DeliveryFeatures.of(new DeliveryFeatures.Input("A", "China", "wire", "S1", 120, 30,
                8, 0.6, 2.5, 33, true, D0, true));
        List<String> names = List.of(v.names());
        assertThat(names).contains("log_qty", "promised_days", "prev_orders", "has_prev", "prev_late_rate",
                "prev_mean_slip", "prev_mean_actual", "doy_sin", "doy_cos", "supplier=A", "country=China", "cat=wire",
                "store=S1");
        assertThat(v.values()[names.indexOf("prev_late_rate")]).isEqualTo(0.6);
        assertThat(v.values()[names.indexOf("promised_days")]).isEqualTo(30.0);

        DeliveryFeatures.Vector thin = DeliveryFeatures.of(new DeliveryFeatures.Input("A", null, null, null, 120, 30,
                1, 1.0, 9, 40, false, D0, false));
        List<String> thinNames = List.of(thin.names());
        assertThat(v.values()[names.indexOf("has_prev")]).isEqualTo(1.0);
        assertThat(thin.values()[thinNames.indexOf("has_prev")]).isZero();
        assertThat(thin.values()[thinNames.indexOf("prev_late_rate")]).as("a record of one order is not used").isZero();
        assertThat(thinNames).doesNotContain("doy_sin", "country=null", "cat=null");
    }
}
