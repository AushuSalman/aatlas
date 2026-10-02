package com.aatlas.demandmodel.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeeklyGridTest {

    private static final LocalDate W0 = LocalDate.of(2026, 1, 5); // a Monday

    @Test
    @DisplayName("missing weeks are zero rows that carry the last price and cost; lags shift week by week")
    void denseGrid() {
        List<WeeklyGrid.SalesWeek> sales = List.of(
                new WeeklyGrid.SalesWeek("A", "S1", "pipe", W0, 10, 5.0, 3.0),
                new WeeklyGrid.SalesWeek("A", "S1", "pipe", W0.plusWeeks(2), 4, 6.0, 0));
        List<WeeklyGrid.CompetitorObs> comps = List.of(new WeeklyGrid.CompetitorObs("A", W0.plusWeeks(1), 7.0));

        WeeklyGrid.Grid g = WeeklyGrid.build(sales, comps, W0.plusWeeks(3), 2);

        assertThat(g.rows()).hasSize(4);
        assertThat(g.firstWeek()).isEqualTo(W0);
        assertThat(g.spanWeeks()).isEqualTo(4);
        WeeklyGrid.Row r0 = g.rows().get(0);
        assertThat(r0.units()).isEqualTo(10);
        assertThat(r0.price()).isEqualTo(5.0);
        assertThat(r0.prevPrice()).isEqualTo(5.0);
        assertThat(r0.compMedian()).as("observed after this week's end").isZero();
        assertThat(r0.lags()).containsOnly(0.0);

        WeeklyGrid.Row r1 = g.rows().get(1);
        assertThat(r1.units()).isZero();
        assertThat(r1.price()).as("carried").isEqualTo(5.0);
        assertThat(r1.cost()).isEqualTo(3.0);
        assertThat(r1.lags()[0]).isEqualTo(10);

        WeeklyGrid.Row r2 = g.rows().get(2);
        assertThat(r2.price()).isEqualTo(6.0);
        assertThat(r2.prevPrice()).isEqualTo(5.0);
        assertThat(r2.cost()).as("no cost on the line: carried").isEqualTo(3.0);
        assertThat(r2.compMedian()).isEqualTo(7.0);
        assertThat(r2.lags()[0]).isZero();
        assertThat(r2.lags()[1]).isEqualTo(10);
        assertThat(r2.medianPrice()).isEqualTo(5.5);

        WeeklyGrid.Context c = g.contexts().get("A@S1");
        assertThat(c.lastPrice()).isEqualTo(6.0);
        assertThat(c.weeks()).isEqualTo(4);
        assertThat(c.units()).isEqualTo(14);
        assertThat(c.lags()[0]).isZero();
        assertThat(c.lags()[1]).isEqualTo(4);
        assertThat(c.lags()[3]).isEqualTo(10);
    }

    @Test
    @DisplayName("a pair spanning fewer weeks than the minimum is left out")
    void shortPairs() {
        List<WeeklyGrid.SalesWeek> sales = List.of(new WeeklyGrid.SalesWeek("A", "S1", null, W0, 10, 5.0, 3.0));
        WeeklyGrid.Grid g = WeeklyGrid.build(sales, List.of(), W0.plusWeeks(3), 6);
        assertThat(g.rows()).isEmpty();
        assertThat(g.contexts()).isEmpty();
    }

    @Test
    @DisplayName("the last complete week is the Monday before this week's Monday")
    void lastCompleteWeek() {
        assertThat(WeeklyGrid.lastCompleteWeek(LocalDate.of(2026, 10, 1))).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(WeeklyGrid.lastCompleteWeek(LocalDate.of(2026, 9, 28))).isEqualTo(LocalDate.of(2026, 9, 21));
        assertThat(WeeklyGrid.lastCompleteWeek(LocalDate.of(2026, 9, 27))).isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    @DisplayName("features: price enters as level, distance from usual and move; one-hots carry category, store and item")
    void features() {
        Features.Vector v = Features.of(new Features.Input(11.0, 10.0, 12.0, new double[] {3, 0, 2, 0, 0, 0, 0, 0},
                10.0, W0, "pipe", "S1", "A", true, true, true));
        List<String> names = List.of(v.names());
        assertThat(names).contains("rel_price", "comp_ratio", "lag1", "mean8", "active8", "woy_sin",
                "cat=pipe", "store=S1", "item=A");
        assertThat(names).as("absolute price would identify the item; a zero move would mark a week with no sales")
                .doesNotContain("log_price", "margin", "price_move");
        Features.Vector noSeason = Features.of(new Features.Input(11.0, 10.0, 12.0, new double[] {3, 0, 2, 0, 0, 0, 0, 0},
                10.0, W0, "pipe", "S1", "A", true, false, true));
        assertThat(List.of(noSeason.names())).doesNotContain("woy_sin", "woy_cos");
        Features.Vector noLags = Features.of(new Features.Input(11.0, 10.0, 12.0, new double[] {3, 0, 2, 0, 0, 0, 0, 0},
                10.0, W0, "pipe", "S1", "A", true, true, false));
        assertThat(List.of(noLags.names())).as("the response model never sees the lags")
                .doesNotContain("lag1", "mean8", "active8").contains("rel_price", "item=A");
        assertThat(v.values()[names.indexOf("rel_price")]).isCloseTo(Math.log(1.1), org.assertj.core.data.Offset.offset(1e-9));
        assertThat(v.values()[names.indexOf("has_comp")]).isEqualTo(1.0);
        assertThat(v.values()[names.indexOf("active8")]).isEqualTo(0.25);
        assertThat(Features.units(Features.target(17))).isCloseTo(17, org.assertj.core.data.Offset.offset(1e-9));
    }
}
