package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** The scorecard: six months of on-time record, spend to date, and how long they've been on the panel. */
@Schema(name = "SupplierPerformanceResponse")
record PerformanceResponse(List<Double> otifTrend, BigDecimal spendYtd, int poCount12m, LocalDate since) {
}
