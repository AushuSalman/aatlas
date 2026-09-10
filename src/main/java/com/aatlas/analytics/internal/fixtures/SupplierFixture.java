package com.aatlas.analytics.internal.fixtures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code record} object of one entry in {@code seed/suppliers.json} - the frontend's
 * {@code SupplierRecord} (data.ts), the Buy screen's full panel row, read verbatim rather
 * than re-derived. {@code suppliers}' own seeding (wave 1) reads the same file's
 * {@code profile}/{@code terms} objects for the rated panel; this module only needs the
 * {@code record} half, which already carries every field the procurement ledger and the
 * Buying insights scorecard read (price index, lead time, OTIF, vendor code, credit days,
 * quality PPM).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SupplierFixture(
        String id,
        String name,
        String country,
        int leadTimeDays,
        double otifPct,
        double priceIndex,
        String vendorCode,
        int creditDays,
        int qualityPpm) {
}
