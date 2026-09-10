package com.aatlas.analytics.internal.ledger;

import java.time.LocalDate;

/** A resolved window - preset or custom. Ported from {@code platform/procurement.ts}'s {@code DateRange}. */
public record DateRange(String key, LocalDate from, LocalDate to, String label, int days) {
}
