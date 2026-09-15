package com.aatlas.catalog.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Editing a branch. Every field is optional; only what is sent changes.
 *
 * <p>Covers the two editors that look different on screen and are the same operation
 * underneath: the branch form, which posts the whole record back, and the "place this
 * branch" prompt after an import, which sends a region and nothing else.
 *
 * <p>{@code store_id} is accepted so a client can PATCH back exactly what it read, but it
 * cannot change - see {@link StoreEntity#getStoreCode()} for why. Sending a different one
 * is a 400 rather than a silent no-op, because a rename that appears to work and does not
 * is the worse failure.
 *
 * <p>Absent and null both mean "leave it", as they do on a supplier patch. A patch
 * therefore cannot empty a column - a branch that was given the wrong MSA is corrected,
 * not blanked. If clearing is ever needed it wants an explicit spelling of its own
 * ({@code "msa_name": ""}), not a null that today's clients send by accident.
 */
@Schema(name = "PatchStoreRequest", description = "A partial edit. Absent keys are left alone.")
record PatchStoreRequest(
        @JsonProperty("store_id")
                @Schema(description = "Accepted only when it matches the branch's current code.")
                        String storeId,

        @JsonProperty("legal_name") @Size(min = 1, max = 200, message = "A branch needs a name.") String legalName,
        @JsonProperty("company_number") @Size(max = 40) String companyNumber,
        @Size(max = 10) String state,
        @JsonProperty("msa_name") @Size(max = 200) String msaName,

        @DecimalMin(value = "1", message = "RPP is an index around 100, not a fraction.")
                @DecimalMax(value = "9999.9")
                BigDecimal rpp,

        @Min(0) Integer txns,
        @JsonProperty("item_count") @Min(0) Integer itemCount,
        @Schema(allowableValues = {"regular", "occasional"}) @Size(max = 20) String segment,
        @Schema(example = "US") @Size(max = 2) String country,

        @Schema(description = "Placing an imported branch is this field on its own.",
                        allowableValues = {"south", "west", "north", "east", "unassigned"})
                @Size(max = 20)
                String regionKey,

        @Valid CreateStoreRequest.MapInput map,

        @Schema(description = "false retires the branch without losing it or its history.")
                Boolean active) {
}
