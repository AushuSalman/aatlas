package com.aatlas.decisions;

import com.aatlas.history.SalesHistory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * What applied prices actually did - measured sales before against after - and what that
 * teaches the pricing model.
 *
 * <p>Each measured decision with a real price change and sales on both sides gives an observed
 * price sensitivity: {@code ln(units after / units before) / ln(price after / price before)}. Their
 * median for an item is blended into the sensitivity the sell chain uses, weighted by how many
 * there are, so a price rise that kept its volume lets the model price higher next time, and one
 * that lost it pulls the model back.
 */
public interface DecisionOutcomes {

    /** Look back this far for an item's measured outcomes. */
    int LEARN_DAYS = 365;

    /** The median observed sensitivity for an item, from its measured decisions; empty with none. */
    Optional<Learned> learned(String itemNumber, LocalDate today);

    /**
     * The sell chain's sensitivity with the item's measured outcomes blended in: on their own when
     * nothing else was measured, else weighted against the sales-history estimate (each outcome
     * counts as three months of history). {@code base} unchanged when the item has none.
     */
    SalesHistory.Elasticity blend(SalesHistory.Elasticity base, String itemNumber, LocalDate today);

    /** The outcome of each measured deal, by deal key - Decision history's result column. */
    Map<String, Outcome> byDeal();

    /** @param n measured decisions it rests on */
    record Learned(BigDecimal elasticity, int n) {
    }

    /**
     * @param verdict {@code worked}, {@code hurt}, {@code neutral} or {@code insufficient}
     * @param label   one line for the screen, e.g. "Volume −12% · profit +6%"
     */
    record Outcome(String dealKey, String verdict, String label, BigDecimal volumeChangePct,
            BigDecimal profitChangePct, BigDecimal priceChangePct, LocalDate measuredOn) {
    }
}
