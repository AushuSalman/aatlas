package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Fulfilment risk, 0-100, with the factors behind it. A port of {@code SupplierRisk} in the
 * frontend's {@code intel/buy2.ts}.
 *
 * <p>The {@code suppliers} module (wave 1) ported the same shape internally but exposes no
 * public reader for it - see {@link CommercialTerms} for the same situation and the same
 * fix: {@code buy} carries its own deterministic copy, keyed the same way (supplier id,
 * country, lead days, otif, defect rate).
 *
 * <p>TODO(merge): consider depending on suppliers' public reader if one exists after merge.
 *
 * @param score 0 = no risk, 100 = certain trouble
 * @param level Low under 35, Medium under 60, High otherwise
 */
@Schema(name = "SupplierRisk")
public record SupplierRisk(
        int score,
        String level,
        String capacity,
        double leadVarianceDays,
        String consistency,
        String trend,
        double recentDelayPct,
        List<Factor> factors) {

    public record Factor(String label, String value, boolean good) {
    }
}
