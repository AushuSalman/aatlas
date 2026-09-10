package com.aatlas.policy.internal;

import com.aatlas.common.error.ApiException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.HttpStatus;

/**
 * How far each guardrail may be moved.
 *
 * <p>Copied from {@code GUARDRAIL_FIELDS} in the frontend's
 * {@code src/lib/intel/guardrails.ts}, and pinned by {@code GuardrailLimitsTest}: the
 * form clamps to these and the API refuses outside them, and the two must agree or a
 * value the form accepts would be a 400 from the API.
 *
 * <p>The string constants exist because {@code @DecimalMin} / {@code @DecimalMax} on
 * {@link GuardrailsRequest} need compile-time constants; {@link #RANGES} is the same
 * numbers for the service-side check and the test.
 */
final class GuardrailLimits {

    static final String MIN_MARGIN_MIN = "5";
    static final String MIN_MARGIN_MAX = "60";
    static final String MAX_DISCOUNT_MIN = "0";
    static final String MAX_DISCOUNT_MAX = "40";
    static final String MAX_SPEED_PREMIUM_MIN = "0";
    static final String MAX_SPEED_PREMIUM_MAX = "25";
    static final String MAX_MARKET_DEVIATION_MIN = "0";
    static final String MAX_MARKET_DEVIATION_MAX = "30";

    /** One guardrail's field name, floor and ceiling, inclusive. */
    record Range(String field, BigDecimal min, BigDecimal max, Function<GuardrailValues, BigDecimal> read) {

        boolean admits(BigDecimal value) {
            return value != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
        }
    }

    static final List<Range> RANGES = List.of(
            new Range("minMarginPct", new BigDecimal(MIN_MARGIN_MIN), new BigDecimal(MIN_MARGIN_MAX),
                    GuardrailValues::minMarginPct),
            new Range("maxDiscountPct", new BigDecimal(MAX_DISCOUNT_MIN), new BigDecimal(MAX_DISCOUNT_MAX),
                    GuardrailValues::maxDiscountPct),
            new Range("maxSpeedPremiumPct", new BigDecimal(MAX_SPEED_PREMIUM_MIN),
                    new BigDecimal(MAX_SPEED_PREMIUM_MAX), GuardrailValues::maxSpeedPremiumPct),
            new Range("maxMarketDeviationPct", new BigDecimal(MAX_MARKET_DEVIATION_MIN),
                    new BigDecimal(MAX_MARKET_DEVIATION_MAX), GuardrailValues::maxMarketDeviationPct));

    private GuardrailLimits() {
    }

    /**
     * Refuses values outside the ranges with a 400 the form can show field by field.
     *
     * <p>Bean validation on the request already does this for HTTP callers; this is the
     * check that also guards the defaults file and any future caller that is not a
     * controller, so the database constraint is never the first thing to say no.
     */
    static void check(GuardrailValues values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Range range : RANGES) {
            BigDecimal value = range.read().apply(values);
            if (!range.admits(value)) {
                fields.put(range.field(), "Use a value between " + range.min().toPlainString() + " and "
                        + range.max().toPlainString() + ".");
            }
        }
        if (!fields.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "One or more guardrails are out of range.", Map.of("fields", fields));
        }
    }
}
