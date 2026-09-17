package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One supplier's landed quote for an item into a destination. The frontend's {@code
 * SupplierQuote}.
 *
 * <p>{@code exWorksCost}/{@code freightCost}/{@code dutyCost}/{@code unitCost} are null when
 * this supplier has no quote on file for the item ({@code supplier_products.ex_works} absent
 * and no purchase history to fall back to): the row still lists the supplier (lead time and
 * OTIF, so it can still be compared and scored on delivery), but it is never awarded and
 * never carries a fabricated number.
 *
 * @param exWorksSource {@code price-list} ({@code supplier_products.ex_works_source} in
 *     {@code import}/{@code manual}), {@code purchases} ({@code ex_works_source='purchases'}
 *     or the item/supplier's own trailing-12m purchase history), or null with no quote
 * @param landedSource {@code observed-90d}/{@code observed-12m} (this item, this supplier, at
 *     least three POs) or {@code lane-estimate} (ex-works marked up by the freight/duty lane);
 *     null with no quote
 */
@Schema(name = "SupplierQuote")
public record SupplierQuote(
        String supplierId,
        String name,
        String country,
        BigDecimal exWorksCost,
        BigDecimal freightCost,
        BigDecimal dutyCost,
        BigDecimal unitCost,
        Integer leadTimeDays,
        int transitDays,
        Integer totalLeadDays,
        BigDecimal otifPct,
        boolean isCurrent,
        boolean isIncumbent,
        String exWorksSource,
        LocalDate exWorksAsOf,
        String landedSource) {
}
