package com.aatlas.decisions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.decisions.DecisionOutcomes;
import com.aatlas.history.SalesHistory;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OutcomeMathTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static OutcomeMath.Side side(String units, String revenue, long txns) {
        return new OutcomeMath.Side(d(units), d(revenue), txns);
    }

    @Test
    void aPriceRiseThatKeptMostOfItsVolumeWorked() {
        // 40 units at $10 before; 36 at $11 after (price +10%, volume -10%), cost $6.
        OutcomeMath.Result r = OutcomeMath.measure(side("40", "400", 20), side("36", "396", 18), 28, d("6"),
                d("10"), d("11"));
        assertThat(r.status()).isEqualTo("measured");
        assertThat(r.priceChangePct()).isEqualByComparingTo("10.00");
        assertThat(r.volumeChangePct()).isEqualByComparingTo("-10.00");
        // profit a week: 10 x $4 = $40 before, 9 x $5 = $45 after: +12.5%
        assertThat(r.profitChangePct()).isEqualByComparingTo("12.50");
        assertThat(r.verdict()).isEqualTo("worked");
        // ln(0.9) / ln(1.1) = -1.105448…
        assertThat(r.impliedElasticity()).isEqualByComparingTo("-1.1054");
        assertThat(r.label()).isEqualTo("Price +10% · volume −10% · profit +13%");
    }

    @Test
    void aPriceRiseThatLostTheVolumeHurt() {
        OutcomeMath.Result r = OutcomeMath.measure(side("40", "400", 20), side("20", "220", 10), 28, d("6"),
                d("10"), d("11"));
        assertThat(r.verdict()).isEqualTo("hurt");
        assertThat(r.impliedElasticity().doubleValue()).isLessThan(-6 + 0.001 + 1); // ln(0.5)/ln(1.1) = -7.27, clamped
        assertThat(r.impliedElasticity()).isEqualByComparingTo("-6.0000");
    }

    @Test
    void tooFewSalesClaimsNothing() {
        OutcomeMath.Result r = OutcomeMath.measure(side("0", "0", 0), side("3", "33", 1), 28, d("6"), d("10"), d("11"));
        assertThat(r.status()).isEqualTo("insufficient");
        assertThat(r.verdict()).isEqualTo("insufficient");
        assertThat(r.impliedElasticity()).isNull();
    }

    @Test
    void aSmallPriceMoveGivesNoSensitivity() {
        OutcomeMath.Result r = OutcomeMath.measure(side("40", "400", 20), side("40", "404", 20), 28, d("6"),
                d("10"), d("10.10"));
        assertThat(r.impliedElasticity()).isNull();
        assertThat(r.status()).isEqualTo("measured");
    }

    @Test
    void outcomesStandInForAnUnmeasuredItemAndAreWeightedAgainstMeasuredHistory() {
        DecisionOutcomes.Learned two = new DecisionOutcomes.Learned(d("-0.8"), 2);

        SalesHistory.Elasticity alone = OutcomesJdbc.blend(SalesHistory.Elasticity.defaultValue(), two);
        assertThat(alone.coefficient()).isEqualByComparingTo("-0.8");
        assertThat(alone.basis()).isEqualTo("outcomes");

        // 12 months measured at -2.0 against 2 outcomes x 3 months at -0.8: (12x-2 + 6x-0.8) / 18 = -1.6
        SalesHistory.Elasticity measured = new SalesHistory.Elasticity(d("-2.0"), d("0.5"), 12,
                SalesHistory.Elasticity.ITEM, d("0.3"));
        SalesHistory.Elasticity mixed = OutcomesJdbc.blend(measured, two);
        assertThat(mixed.coefficient()).isEqualByComparingTo("-1.6");
        assertThat(mixed.basis()).isEqualTo("item+outcomes");
        assertThat(mixed.n()).isEqualTo(14);
    }
}
