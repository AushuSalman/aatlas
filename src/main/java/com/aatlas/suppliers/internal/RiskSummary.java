package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** The two risk figures the panel row shows; the full breakdown is at {@code /{id}/risk}. */
@Schema(name = "RiskSummary")
record RiskSummary(int score, String level) {
}
