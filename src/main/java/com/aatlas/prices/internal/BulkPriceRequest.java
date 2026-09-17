package com.aatlas.prices.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code POST /prices/bulk}: the rows to write, their source and the date they apply from.
 *
 * @param source {@code wizard} or {@code manual}; wizard when absent
 * @param effectiveFrom today when absent
 */
record BulkPriceRequest(
        @NotEmpty @Size(max = PricesService.MAX_ROWS) List<@Valid Row> rows,
        String source,
        LocalDate effectiveFrom) {

    /**
     * One row. A list price, a cost, or both.
     *
     * @param store null for tenant-wide
     * @param basis the suggestion's basis, stored verbatim
     */
    record Row(@NotBlank String item, String store, BigDecimal listPrice, BigDecimal cost, Map<String, Object> basis) {
    }
}
