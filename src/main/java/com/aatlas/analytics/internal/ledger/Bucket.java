package com.aatlas.analytics.internal.ledger;

import java.time.LocalDate;

/** One column of a chart. Ported from {@code platform/procurement.ts}'s {@code Bucket}. */
public record Bucket(String key, String label, String title, LocalDate from, LocalDate to, boolean partial) {
}
