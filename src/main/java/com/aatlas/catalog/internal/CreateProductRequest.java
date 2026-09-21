package com.aatlas.catalog.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Adding one item to the master by hand - the single-row form of the products import.
 *
 * <p>Item number and description are required, as they are for a new item in the import.
 * The rest may be blank and take the import's defaults: {@code uncategorised}, commodity
 * {@code none}, unit {@code each}. A list price or cost, when given, goes to the price list
 * tenant-wide, exactly as a products-file row without a branch does.
 */
@Schema(name = "CreateProductRequest", description = "A new item. itemNumber and description are required.")
record CreateProductRequest(
        @Schema(description = "Your internal SKU. Unique per company, and permanent once set.", example = "HRD118902")
                @NotBlank(message = "An item needs an item number.")
                @Size(max = 60, message = "That item number is too long (60 characters at most).")
                String itemNumber,

        @Schema(example = "1/2 IN COPPER TYPE L HARD TUBE 10FT")
                @NotBlank(message = "An item needs a description.")
                @Size(max = 300, message = "That description is too long (300 characters at most).")
                String description,

        @Schema(example = "Plumbing") @Size(max = 80) String category,

        @Schema(example = "Pipe & tube") @Size(max = 80) String subcategory,

        @Schema(description = "The selling unit.", example = "10 ft length") @Size(max = 40) String unit,

        @Schema(description = "A tracked commodity key (copper, brass, steel, iron, pvc, pex, equipment) or none.",
                        example = "copper")
                @Size(max = 40)
                String commodity,

        @Schema(description = "Current selling price, in the company's trading currency.", example = "27.10")
                @DecimalMin(value = "0", message = "A list price cannot be negative.")
                @DecimalMax(value = "9999999999", message = "That list price is too large.")
                BigDecimal listPrice,

        @Schema(description = "Current unit cost, in the company's trading currency.", example = "19.85")
                @DecimalMin(value = "0", message = "A unit cost cannot be negative.")
                @DecimalMax(value = "9999999999", message = "That unit cost is too large.")
                BigDecimal unitCost) {
}
