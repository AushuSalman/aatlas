package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The two risk figures the panel row shows; the full breakdown is at {@code /{id}/risk}.
 *
 * @param assessed false when a supplier is known only by name: neither an observed nor a
 *     provided on-time rate or defect rate exists, so {@code score}/{@code level} are null
 *     rather than a placeholder the UI could mistake for a real figure.
 */
@Schema(name = "RiskSummary")
record RiskSummary(Integer score, String level, boolean assessed) {
}
