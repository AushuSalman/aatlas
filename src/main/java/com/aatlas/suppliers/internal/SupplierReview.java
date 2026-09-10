package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/** One thing a buyer said. Mirrors {@code SupplierReview} in the frontend. */
@Schema(name = "SupplierReview")
record SupplierReview(String author, String when, int stars, String text) {
}
