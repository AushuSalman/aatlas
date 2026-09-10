package com.aatlas.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** What {@link ProcurementLedger#recordAward} wrote. */
public record PurchaseOrderRecord(UUID id, String poNumber, LocalDate orderDate, BigDecimal landed, BigDecimal spend) {
}
