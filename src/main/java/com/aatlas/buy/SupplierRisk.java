package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * Fulfilment risk, 0-100, with the factors behind it. Spec-A S3.6: observed lead/OTIF
 * variance and trend when the supplier has at least five received purchase orders in the
 * trailing twelve months, the supplier's file facts otherwise, and - when neither OTIF nor a
 * defect rate is known at all - "not assessed": {@code score}/{@code level} null and
 * {@code factors} empty, never a fabricated 95/High.
 *
 * @param score 0 = no risk, 100 = certain trouble; null when nothing can be assessed
 * @param level Low under 35, Medium under 60, High otherwise; null with {@code score}
 */
@Schema(name = "SupplierRisk")
public record SupplierRisk(
        Integer score,
        String level,
        String capacity,
        BigDecimal leadVarianceDays,
        String consistency,
        String trend,
        BigDecimal recentDelayPct,
        List<Factor> factors) {

    public static SupplierRisk notAssessed() {
        return new SupplierRisk(null, null, null, null, null, null, null, List.of());
    }

    public record Factor(String label, String value, boolean good) {
    }
}
