package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The drafted message and levers for a negotiation with the incumbent. The frontend's {@code Negotiation}. */
@Schema(name = "Negotiation")
public record Negotiation(
        String supplierName,
        double quote,
        double benchmark,
        int annualVolume,
        double target,
        double annualSavings,
        double gapPct,
        int relationshipYears,
        int leadDays,
        double otifPct,
        String message,
        List<String> levers) {
}
