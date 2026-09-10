package com.aatlas.sell.internal.buy;

/** The slice of a supplier ATP allocation reads: id, name, on-time percent, price index. */
public record SupplierRef(String supplierId, String name, double otifPct, double priceIndex) {
}
