package com.aatlas.sell;

import java.math.BigDecimal;
import java.util.List;

/**
 * The frontend's {@code OpportunityScore} in {@code src/lib/intel/score.ts}, field for
 * field: one number per (item, store), the five reasons behind it, and the raw signals for
 * anything that wants to sort or filter on them.
 *
 * <p>Numeric fields are {@link BigDecimal}, never {@code double}/{@code Double}: {@code
 * ArchitectureRulesTest.noFloatingPointMoney} forbids a dependency on {@code
 * java.lang.Double} from this package.
 */
public record OpportunityScoreView(
        String itemNumber,
        String storeId,
        int score,
        String tier,
        String tierLabel,
        List<Reason> reasons,
        Signals signals) {

    public record Reason(String text, boolean good) {
    }

    public record Signals(
            String demandLevel,
            BigDecimal priceGapPct,
            BigDecimal marginPct,
            BigDecimal weeksOfCover,
            int conversionPct,
            BigDecimal commodityPct90) {
    }
}
