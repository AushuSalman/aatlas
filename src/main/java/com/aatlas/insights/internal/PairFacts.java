package com.aatlas.insights.internal;

import com.aatlas.history.Anchor;
import com.aatlas.history.BulkModelReader.PairModel;
import com.aatlas.history.PricingMath;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Everything derived, once, for one (product, store-or-no-branch) pair: the real anchor
 * (competitor -&gt; peer -&gt; benchmark -&gt; history, same chain {@code history.PriceLadder}
 * uses), the demand signal, and the {@code PricingMath} recommendation built from them. Every
 * number here comes from the tenant's own rows through {@link PairModel} or is labelled
 * reference data (the benchmark/commodity rungs); nothing is hashed.
 *
 * <p>Built once per request by {@link InsightsDataLoader} and shared by every engine, so the
 * same pair reads the same cost, current price and score on every screen this module serves.
 */
record PairFacts(
        PairModel pair,
        PricingMath.Demand demand,
        Anchor anchor,
        BigDecimal commodityPct90,
        Optional<PricingMath.Recommendation> recommendation) {

    /** The default elasticity used across insights (no per-pair elasticity call): only the
     * "aggressive" stretch price depends on it, and insights never shows that price. */
    static final double DEFAULT_BETA = -1.2;

    String storeKey() {
        return pair.storeId() == null ? "no-branch" : pair.storeCode();
    }

    boolean priceable() {
        return currentPrice() != null && recommendation.isPresent();
    }

    BigDecimal currentPrice() {
        return pair.currentPrice() == null ? null : pair.currentPrice().value();
    }

    String currentPriceSource() {
        return pair.currentPrice() == null ? null : pair.currentPrice().source();
    }

    BigDecimal cost() {
        return pair.cost() == null ? null : pair.cost().value();
    }

    String costSource() {
        return pair.cost() == null ? null : pair.cost().source();
    }

    BigDecimal optimalPrice() {
        return recommendation.map(PricingMath.Recommendation::optimal).orElse(null);
    }

    BigDecimal ceilingPrice() {
        return recommendation.map(PricingMath.Recommendation::ceiling).orElse(null);
    }

    BigDecimal marginPct() {
        return PricingMath.marginPct(currentPrice(), cost());
    }

    boolean hasInventory() {
        return pair.onHand() != null;
    }

    BigDecimal onHandUnits() {
        return pair.onHand() == null ? null : pair.onHand().units();
    }

    BigDecimal weeksOfCover() {
        return pair.onHand() == null ? null : PricingMath.weeksOfCover(pair.onHand().units(), units90());
    }

    BigDecimal inventoryValue() {
        BigDecimal cost = cost();
        BigDecimal units = onHandUnits();
        return units == null || cost == null ? null : units.multiply(cost);
    }

    BigDecimal units90() {
        return pair.units90() == null ? BigDecimal.ZERO : pair.units90();
    }

    /** {@code round(units90/3)}, else {@code units12m/12}. */
    BigDecimal monthlyUnits() {
        if (pair.units90() != null && pair.units90().signum() > 0) {
            return pair.units90().divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
        }
        BigDecimal u12 = annualUnits();
        return u12.signum() == 0 ? BigDecimal.ZERO : u12.divide(BigDecimal.valueOf(12), 4, RoundingMode.HALF_UP);
    }

    BigDecimal annualUnits() {
        BigDecimal u12 = pair.w12() != null ? pair.w12().units() : null;
        return u12 == null ? BigDecimal.ZERO : u12;
    }

    BigDecimal upliftPerUnit() {
        BigDecimal opt = optimalPrice();
        BigDecimal cur = currentPrice();
        return opt == null || cur == null ? null : opt.subtract(cur);
    }

    BigDecimal upliftPct() {
        return PricingMath.pct(upliftPerUnit(), currentPrice());
    }

    BigDecimal monthlyOpportunity() {
        BigDecimal u = upliftPerUnit();
        return u == null ? null : u.multiply(monthlyUnits());
    }

    /** {@code 1 − ((rpp − 100) / 100) × 0.55}; 1 with no regional price parity on file. */
    double msaMult() {
        BigDecimal rpp = pair.rpp();
        if (rpp == null) {
            return 1;
        }
        return 1 - ((rpp.doubleValue() - 100) / 100) * 0.55;
    }

    PricingMath.Score score(BigDecimal followRatePct, int decisions) {
        return PricingMath.score(new PricingMath.ScoreInputs(
                demand == null ? null : demand.level(),
                anchor == null ? null : anchor.value(),
                currentPrice(),
                commodityPct90,
                marginPct(),
                weeksOfCover(),
                followRatePct,
                decisions));
    }

    /** Real reasons behind {@link #score}, good news first - mirrors {@code PricingMath.score}'s own weights. */
    List<ScoreEngine.ScoreReason> scoreReasons(PricingMath.Score s) {
        return ScoreEngine.reasonsFor(this, s);
    }
}
