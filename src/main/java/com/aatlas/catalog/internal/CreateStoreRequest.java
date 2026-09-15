package com.aatlas.catalog.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Opening a branch.
 *
 * <p>Two fields are required and the rest are placement and trading detail that a branch
 * may genuinely not have on its first day. The keys are the ones {@link StoreView}
 * returns, snake_case included, so the form that renders a branch can post one back
 * without a mapping layer.
 *
 * <p><b>Nothing is guessed.</b> Send {@code state} and the market region follows from the
 * reference tables; send neither and the branch opens {@code unassigned}, which is a real
 * state rather than a placeholder - it keeps the branch out of every regional rollup and
 * shows on screen as work to do. The alternative, picking a plausible region, puts a
 * branch in the wrong market and nobody ever finds out.
 */
@Schema(name = "CreateStoreRequest", description = "A new branch. store_id and legal_name are required.")
record CreateStoreRequest(
        @JsonProperty("store_id")
                @Schema(description = "The branch code people and the ERP use. Unique per company, and "
                        + "permanent once set.", example = "100959")
                @NotBlank(message = "A branch needs a code.")
                @Size(max = 40, message = "That branch code is too long.")
                String storeId,

        @JsonProperty("legal_name")
                @Schema(example = "Dallas Branch")
                @NotBlank(message = "A branch needs a name.")
                @Size(max = 200)
                String legalName,

        @JsonProperty("company_number") @Size(max = 40) String companyNumber,

        @Schema(description = "US state or UK region code. Places the branch in a market region.",
                        example = "TX")
                @Size(max = 10)
                String state,

        @JsonProperty("msa_name") @Size(max = 200) String msaName,

        @Schema(description = "Regional price parity, 100 = national average. Omit to price nationally.")
                @DecimalMin(value = "1", message = "RPP is an index around 100, not a fraction.")
                @DecimalMax(value = "9999.9")
                BigDecimal rpp,

        @Min(0) Integer txns,
        @JsonProperty("item_count") @Min(0) Integer itemCount,

        @Schema(allowableValues = {"regular", "occasional"}) @Size(max = 20) String segment,

        @Schema(description = "Defaults to the company's country. Must match the other branches.",
                        example = "US")
                @Size(max = 2)
                String country,

        @Schema(description = "Omit to let `state` decide, or to open the branch unassigned.",
                        allowableValues = {"south", "west", "north", "east", "unassigned"})
                @Size(max = 20)
                String regionKey,

        @Schema(description = "Where the dot sits on the 960x520 country map.") @Valid MapInput map,

        @Schema(description = "Defaults to true. Send false to open a branch that is not trading yet.")
                Boolean active) {

    /**
     * The map position as input.
     *
     * <p>Its own type rather than {@link StoreView.MapPoint} because the bounds belong on
     * the way in: a dot at x=40000 is accepted by the column and lands off the canvas.
     */
    @Schema(name = "MapPointInput")
    record MapInput(
            @Min(0) @Max(960) Integer x,
            @Min(0) @Max(520) Integer y,
            @Schema(allowableValues = {"start", "end"}) @Size(max = 10) String anchor) {
    }
}
