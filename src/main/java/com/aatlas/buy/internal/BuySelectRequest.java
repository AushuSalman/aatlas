package com.aatlas.buy.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code POST /buy/select}'s body. The frontend's {@code BuySelectInput} in {@code
 * platform/backend.ts}.
 *
 * <p>{@code qty} is optional (defaults to 1 unit when omitted, so existing callers keep
 * working): when given, an immediately-approved award is written as a real deal onto the
 * {@code decisions} ledger with that quantity, instead of only into this module's own
 * {@code buy_decisions} audit row - see {@code BuyService#select}.
 *
 * <p>{@code supplierId} is the supplier actually being awarded - the panel row the frontend
 * chose ({@code ProcurementOption.suppliers[0].id}), not {@code optionKey} (which names the
 * option, "cost"/"speed"/"balanced"/"reliability", and is kept only for the audit row). Null
 * for an older frontend build; {@link BuyService} falls back to the item's incumbent supplier
 * in that case rather than misreading the option key as a supplier id.
 *
 * <p>{@code unitCost} is what was actually agreed, per unit landed - the quote that won an
 * RFQ, or the price on a purchase somebody is recording after the fact. Without it the only
 * cost this endpoint knew was its own model's landed estimate for the supplier, so a buyer
 * who negotiated 4% under it had the estimate written to the ledger instead of the deal they
 * made, and every saving the history screen reports was the model grading itself. Optional,
 * so a caller that does not know the agreed price keeps the old behaviour.
 *
 * <p>{@code purchasedOn} lets a purchase that already happened be recorded on its own date
 * rather than today's. Optional; today when omitted.
 */
@Schema(name = "BuySelectRequest")
record BuySelectRequest(
        @NotBlank String itemNumber,
        @NotBlank String regionKey,
        @NotBlank String destinationId,
        @NotBlank String optionKey,
        String supplierId,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal orderValue,
        Integer qty,
        @Schema(description = "Agreed landed cost per unit. Omit to record the model's landed estimate.")
                @DecimalMin(value = "0", inclusive = true)
                BigDecimal unitCost,
        @Schema(description = "When the purchase was made. Omit for today.") @PastOrPresent LocalDate purchasedOn) {
}
