package com.aatlas.policy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aatlas.common.error.ApiException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * The four ranges, pinned to the frontend's {@code GUARDRAIL_FIELDS} in
 * {@code src/lib/intel/guardrails.ts}: minMargin 5-60, maxDiscount 0-40, maxSpeedPremium
 * 0-25, maxMarketDeviation 0-30. If either side of this changes without the other, a value
 * the Settings form accepts would come back a 400 from the API.
 */
class GuardrailLimitsTest {

    private static GuardrailValues values(String minMargin, String maxDiscount, String maxSpeed, String maxDeviation) {
        return new GuardrailValues(
                new BigDecimal(minMargin), new BigDecimal(maxDiscount), new BigDecimal(maxSpeed),
                new BigDecimal(maxDeviation));
    }

    @Test
    @DisplayName("the seeded defaults (25 / 15 / 8 / 10) are inside every range")
    void defaultsAreInsideRange() {
        assertThatCode(() -> GuardrailLimits.check(values("25", "15", "8", "10"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every boundary is inclusive")
    void boundariesAreInclusive() {
        assertThatCode(() -> GuardrailLimits.check(values("5", "0", "0", "0"))).doesNotThrowAnyException();
        assertThatCode(() -> GuardrailLimits.check(values("60", "40", "25", "30"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one step past either boundary is rejected")
    void justOutsideTheBoundaryIsRejected() {
        assertThatThrownBy(() -> GuardrailLimits.check(values("4.99", "0", "0", "0")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> GuardrailLimits.check(values("60.01", "0", "0", "0")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> GuardrailLimits.check(values("25", "40.01", "0", "0")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> GuardrailLimits.check(values("25", "15", "25.01", "0")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> GuardrailLimits.check(values("25", "15", "8", "30.01")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a violation is 400 validation_failed, naming the field")
    void violationCarriesTheField() {
        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, () -> GuardrailLimits.check(values("2", "15", "8", "10")));

        assertThat(thrown.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thrown.code()).isEqualTo("validation_failed");
        @SuppressWarnings("unchecked")
        var fields = (java.util.Map<String, Object>) thrown.details().get("fields");
        assertThat(fields).containsKey("minMarginPct");
    }

    @Test
    @DisplayName("more than one bad value names every field, not just the first")
    void allViolatingFieldsAreNamed() {
        ApiException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ApiException.class, () -> GuardrailLimits.check(values("2", "99", "8", "10")));

        @SuppressWarnings("unchecked")
        var fields = (java.util.Map<String, Object>) thrown.details().get("fields");
        assertThat(fields).containsKeys("minMarginPct", "maxDiscountPct");
        assertThat(fields).doesNotContainKeys("maxSpeedPremiumPct", "maxMarketDeviationPct");
    }
}
