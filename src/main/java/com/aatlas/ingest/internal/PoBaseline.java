package com.aatlas.ingest.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * The baseline/target arithmetic of spec A 3.6a, in Java.
 *
 * <p>The loader does not call this: it runs the equivalent SQL window pass tenant-wide after
 * every purchases commit and rollback ({@link PurchaseOrderLoader#BASELINE_SQL}), so targets
 * are a property of the data rather than of load order. This class states the same rule in a
 * form a unit test can pin, and is what a reader should check the SQL against.
 *
 * <p>For an order of an item: the <b>target</b> is the best (lowest) landed cost paid for the
 * item in the 365 days before the order date; the <b>baseline</b> is the quantity-weighted
 * average landed cost over the same window; an order is <b>followed</b> when its landed cost
 * is within 0.5% of the target; <b>saved</b> is what was paid under the baseline, <b>leaked</b>
 * what was paid over the target, both times quantity. A first order is its own baseline and
 * target, followed, with nothing saved or leaked.
 */
final class PoBaseline {

    private static final BigDecimal FOLLOW_TOLERANCE = new BigDecimal("1.005");

    /** An earlier order of the same item. */
    record Prior(LocalDate orderDate, BigDecimal landed, int qty) {
    }

    record Result(BigDecimal target, BigDecimal baseline, boolean followed, BigDecimal baselineSpend,
            BigDecimal saved, BigDecimal leaked) {
    }

    private PoBaseline() {
    }

    static Result compute(LocalDate orderDate, BigDecimal landed, int qty, List<Prior> priors) {
        LocalDate from = orderDate.minusDays(365);
        LocalDate to = orderDate.minusDays(1);
        BigDecimal best = null;
        BigDecimal weighted = BigDecimal.ZERO;
        long units = 0;
        for (Prior p : priors) {
            if (p.orderDate().isBefore(from) || p.orderDate().isAfter(to)) {
                continue;
            }
            best = best == null || p.landed().compareTo(best) < 0 ? p.landed() : best;
            weighted = weighted.add(p.landed().multiply(BigDecimal.valueOf(p.qty())));
            units += p.qty();
        }
        BigDecimal target = best == null ? landed : best;
        BigDecimal baseline = units == 0 ? landed
                : weighted.divide(BigDecimal.valueOf(units), 4, RoundingMode.HALF_UP);
        BigDecimal q = BigDecimal.valueOf(qty);
        boolean followed = landed.compareTo(target.multiply(FOLLOW_TOLERANCE)) <= 0;
        BigDecimal saved = baseline.subtract(landed).max(BigDecimal.ZERO).multiply(q).setScale(4, RoundingMode.HALF_UP);
        BigDecimal leaked = landed.subtract(target).max(BigDecimal.ZERO).multiply(q).setScale(4, RoundingMode.HALF_UP);
        return new Result(target.setScale(4, RoundingMode.HALF_UP), baseline, followed,
                baseline.multiply(q).setScale(4, RoundingMode.HALF_UP), saved, leaked);
    }
}
