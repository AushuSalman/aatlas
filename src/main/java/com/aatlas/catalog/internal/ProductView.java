package com.aatlas.catalog.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * An item as the frontend's {@code ProductOption} plus its {@code ProductMeta}: one row
 * that both pickers and the intelligence layer can read without a second call.
 *
 * <p>{@code value} and {@code label} repeat the item number because {@code ProductOption}
 * requires them for the select control; sending them costs nothing and saves a mapping.
 */
@Schema(name = "Product")
record ProductView(
        @Schema(example = "HRD118902") String itemNumber,
        @Schema(description = "Same as itemNumber; the picker's option value.") String value,
        @Schema(description = "Same as itemNumber; the picker's option label.") String label,
        @Schema(example = "1/2 IN COPPER TYPE L HARD TUBE 10FT") String description,
        @Schema(description = "The branch the demo story opens on for this item.", example = "100959")
                String defaultTenant,
        @Schema(description = "False = catalogued but never sold.") boolean hasSales,
        @Schema(description = "True = a current list price is on file, at any branch or tenant-wide. "
                + "Priceable means hasSales or hasPrice.") boolean hasPrice,
        @Schema(example = "Copper Tube 1/2\" Type L") String shortName,
        @Schema(example = "Plumbing") String category,
        @Schema(example = "Pipe & tube") String subcategory,
        @Schema(example = "copper") String commodity,
        @Schema(example = "10 ft length") String unit) {

    static ProductView of(ProductEntity product, boolean hasPrice) {
        return new ProductView(
                product.getItemNumber(),
                product.getItemNumber(),
                product.getItemNumber(),
                product.getDescription(),
                product.getDefaultStoreCode(),
                product.isHasSales(),
                hasPrice,
                product.getShortName(),
                product.getCategory(),
                product.getSubcategory(),
                product.getCommodity(),
                product.getUnit());
    }
}
