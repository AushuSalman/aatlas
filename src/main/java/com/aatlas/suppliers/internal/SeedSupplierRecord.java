package com.aatlas.suppliers.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The Buy-side fields of {@code seed/suppliers.json}'s {@code record} object that the
 * {@code suppliers} table needs but the frontend's {@code SupplierProfile} does not carry:
 * the vendor code, the named contact, the order-mechanics columns, and what has been bought
 * from them so far. Everything else on {@code record} (defect rate, OTIF, price index,
 * commercial terms) is also seeded on {@code profile} / {@code terms} and read from there,
 * so this type ignores the rest rather than duplicating it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SeedSupplierRecord(
        String id,
        String vendorCode,
        String contactName,
        String email,
        String currency,
        int moq,
        int orderMultiple,
        int qualityPpm,
        int responseHours,
        BigDecimal spendYtd,
        int poCount12m,
        LocalDate since,
        List<Double> otifTrend) {
}
