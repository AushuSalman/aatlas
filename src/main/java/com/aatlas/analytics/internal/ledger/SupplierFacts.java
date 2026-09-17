package com.aatlas.analytics.internal.ledger;

import java.math.BigDecimal;

/**
 * What the supplier panel and its commercial terms have on file for one supplier id
 * ({@code purchase_order.supplier_id}, which is {@code suppliers.supplier_key}), as the
 * Buying insights scorecard reads it. Every field is {@code null} when the panel does not
 * carry it - a supplier known only from an imported purchase order (see V22) has none of
 * these - never a placeholder the scorecard would present as a fact.
 */
record SupplierFacts(String vendorCode, BigDecimal priceIndex, Integer leadTimeDays, Integer qualityPpm,
        Integer creditDays) {

    static final SupplierFacts EMPTY = new SupplierFacts(null, null, null, null, null);
}
