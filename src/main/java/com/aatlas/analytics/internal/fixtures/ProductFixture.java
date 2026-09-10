package com.aatlas.analytics.internal.fixtures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One row of {@code seed/products.json}, read directly rather than re-typed: the frontend's
 * {@code ProductOption} (item.ts) plus the meta fields (shortName, category, subcategory,
 * commodity, unit) {@code intel/catalog.ts}'s {@code PRODUCT_META} carries for the same item.
 *
 * <p>{@code analytics} needs this purely for the procurement ledger generator (which needs
 * {@code defaultTenant} and {@code hasSales} to reproduce {@code getPricingModel}'s cost and
 * priceable check) and to label a line with its category and description. It is a read-only
 * copy of the same fixture {@code catalog} seeds into a tenant's {@code products} table - see
 * the module javadoc for why this module reads the fixture file directly rather than
 * {@code catalog}'s rows.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductFixture(
        String itemNumber,
        String description,
        String defaultTenant,
        boolean hasSales,
        String shortName,
        String category,
        String subcategory,
        String commodity,
        String unit) {
}
