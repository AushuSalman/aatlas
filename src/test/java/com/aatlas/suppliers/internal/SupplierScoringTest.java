package com.aatlas.suppliers.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SupplierScoring#overall}: the weighted mean over whichever dimensions are present,
 * with the remaining weights renormalised. Pins the fix for the bug the rewrite found -
 * {@code SupplierWriter} used to average the four stars unweighted, disagreeing with the
 * panel's own weighted figure the moment two suppliers had different-shaped breakdowns.
 */
class SupplierScoringTest {

    private static final double WEIGHT_QUALITY = 0.15;
    private static final double WEIGHT_DELIVERY = 0.4;
    private static final double WEIGHT_COMMUNICATION = 0.3;
    private static final double WEIGHT_PRICING = 0.15;

    @Test
    @DisplayName("all four dimensions present: the stated weights apply directly")
    void allFourDimensionsWeightedAsStated() {
        RatingBreakdown breakdown = new RatingBreakdown(4.0, 5.0, 3.0, 4.0);

        Double overall = SupplierScoring.overall(breakdown);

        double expected = 4.0 * WEIGHT_QUALITY + 5.0 * WEIGHT_DELIVERY + 3.0 * WEIGHT_COMMUNICATION
                + 4.0 * WEIGHT_PRICING;
        assertThat(overall).isCloseTo(expected, within(0.05));
    }

    @Test
    @DisplayName("one dimension absent: the remaining three's weights are renormalised, not zero-filled")
    void oneAbsentDimensionIsSkippedNotZeroed() {
        // No communication star at all (e.g. a purchases-import supplier a person only
        // partly completed): quality .15, delivery .4, pricing .15 renormalise over .70.
        RatingBreakdown breakdown = new RatingBreakdown(4.0, 5.0, null, 4.0);

        Double overall = SupplierScoring.overall(breakdown);

        double weightSum = WEIGHT_QUALITY + WEIGHT_DELIVERY + WEIGHT_PRICING;
        double expected = (4.0 * WEIGHT_QUALITY + 5.0 * WEIGHT_DELIVERY + 4.0 * WEIGHT_PRICING) / weightSum;
        assertThat(overall).isCloseTo(expected, within(0.05));
        // An unweighted mean of the three present dimensions (the pre-fix bug) would have
        // been (4+5+4)/3 = 4.33; the delivery-heavy renormalised weight pulls it higher.
        assertThat(overall).isGreaterThan((4.0 + 5.0 + 4.0) / 3);
    }

    @Test
    @DisplayName("only one dimension present: the overall equals that dimension exactly")
    void onlyOneDimensionPresentIsUnweighted() {
        RatingBreakdown breakdown = new RatingBreakdown(null, 4.2, null, null);

        assertThat(SupplierScoring.overall(breakdown)).isEqualTo(4.2);
    }

    @Test
    @DisplayName("no dimension present: null, not zero - the caller writes no rating row at all")
    void noDimensionsMeansNoRating() {
        RatingBreakdown breakdown = new RatingBreakdown(null, null, null, null);

        assertThat(SupplierScoring.overall(breakdown)).isNull();
    }

    @Test
    @DisplayName("qualityStars and pricingStars are null-safe over an absent input")
    void starsAreNullSafeOverAbsentInputs() {
        assertThat(SupplierScoring.qualityStars(null)).isNull();
        assertThat(SupplierScoring.pricingStars(null)).isNull();
        assertThat(SupplierScoring.qualityStars(0.2)).isNotNull();
        assertThat(SupplierScoring.pricingStars(89.0)).isNotNull();
    }

    @Test
    @DisplayName("deliveryStars uses only the provided holdsStock fact, never a hashed guess")
    void deliveryStarsNeverInventsHoldsStock() {
        // Same lead time and otif, only holdsStock differs: true floors the star, null does not.
        Double withoutStockFact = SupplierScoring.deliveryStars("USA", 20, 90.0, null);
        Double knownToHoldStock = SupplierScoring.deliveryStars("USA", 20, 90.0, true);
        Double knownNotToHoldStock = SupplierScoring.deliveryStars("USA", 20, 90.0, false);

        assertThat(knownToHoldStock).isGreaterThanOrEqualTo(withoutStockFact);
        assertThat(knownNotToHoldStock).isEqualTo(withoutStockFact);
    }

    @Test
    @DisplayName("deliveryStars is null only when both its inputs are absent")
    void deliveryStarsNullOnlyWhenBothInputsAbsent() {
        assertThat(SupplierScoring.deliveryStars("USA", null, null, null)).isNull();
        assertThat(SupplierScoring.deliveryStars("USA", 20, null, null)).isNotNull();
        assertThat(SupplierScoring.deliveryStars("USA", null, 90.0, null)).isNotNull();
    }
}
