package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The supplier panel as the read layer sees it, with the per-item links and whatever
 * commercial terms were provided. A null figure is "not provided", never a default.
 */
public interface Suppliers {

    List<SupplierRef> panel();

    Optional<SupplierRef> supplier(String supplierKey);

    /** Suppliers linked to the item, with the per-pair facts; empty when none are linked. */
    List<SupplierLink> panelFor(UUID productId);

    Optional<Terms> terms(String supplierKey);

    SupplierCoverage coverage();

    record SupplierRef(UUID id, String supplierKey, String name, String country, String vendorCode,
            String category, BigDecimal priceIndex, Integer leadTimeDays, BigDecimal otifPct, BigDecimal defectPct,
            Boolean holdsStock, Integer yearsTrading, LocalDate since, boolean custom, String source) {
    }

    /**
     * One (supplier, product) link.
     *
     * @param exWorks null when no quote is on file
     * @param exWorksSource {@code import}, {@code purchases} or {@code manual}; null with no quote
     */
    record SupplierLink(SupplierRef supplier, BigDecimal exWorks, String exWorksSource, LocalDate exWorksAsOf,
            Integer moq, Integer leadTimeDays) {
    }

    /** {@code supplier_terms}, verbatim; every field may be null when never provided. */
    record Terms(Integer creditDays, String termsLabel, BigDecimal earlyPayDiscountPct, Integer earlyPayDays,
            BigDecimal latePenaltyPctPerWeek, BigDecimal latePenaltyCapPct, Integer warrantyMonths,
            Integer quoteValidityDays, String incoterm, BigDecimal invoiceAccuracyPct, Integer capacityUnitsMonth,
            Integer moq, Integer orderMultiple, Integer qualityPpm, Integer responseHours,
            List<String> certifications) {
    }

    record SupplierCoverage(int count, int withTerms, int withPurchases) {
    }
}
