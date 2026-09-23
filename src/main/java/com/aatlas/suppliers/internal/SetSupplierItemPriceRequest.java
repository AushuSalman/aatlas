package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * What this supplier charges for one item, entered by hand.
 *
 * <p>The ex-works price only: freight and duty into a branch are the lane's, and the buy
 * engine puts them on top. Without this the only way onto a supplier's price list was an
 * import, so a price found on the market or given over the phone had nowhere to go.
 */
@Schema(name = "SetSupplierItemPriceRequest", description = "A supplier's ex-works price for one item.")
record SetSupplierItemPriceRequest(
        @Schema(description = "Ex-works price per unit, before freight and duty.", example = "118.00")
                @NotNull(message = "A price is needed.")
                @DecimalMin(value = "0", message = "A price cannot be negative.")
                @DecimalMax(value = "9999999999", message = "That price is too large.")
                BigDecimal exWorks,

        @Schema(description = "Minimum order quantity, when they quoted one.", example = "100")
                @Min(0)
                Integer moq,

        @Schema(description = "Days from order to delivery for this item, when they quoted one.", example = "17")
                @Min(0)
                @Max(365)
                Integer leadTimeDays) {
}
