package com.aatlas.prices.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@code PUT /prices/{item}}: a list price and/or a cost for one item, at a branch or
 * tenant-wide.
 *
 * @param source {@code manual} when absent
 * @param effectiveFrom today when absent
 */
record SetPriceRequest(String store, BigDecimal listPrice, BigDecimal cost, String source, LocalDate effectiveFrom,
        Map<String, Object> basis) {
}
