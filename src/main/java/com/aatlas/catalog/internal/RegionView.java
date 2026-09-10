package com.aatlas.catalog.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * A market region for this tenant's country: the frontend's {@code MarketRegion} with its
 * subdivisions spelled out and the branches that sit in it.
 *
 * <p>{@code label} is the full name for headings ("South &amp; London"), {@code short} the
 * compass word for chips ("South"); the keys are the same in every country so
 * {@code ?region=south} means the same screen everywhere.
 */
@Schema(name = "Region")
record RegionView(
        @Schema(allowableValues = {"south", "west", "north", "east"}) String key,
        @Schema(description = "Full name, for headings.", example = "South & London") String label,
        @JsonProperty("short") @Schema(description = "Compass word, for chips.", example = "South") String shortLabel,
        @Schema(description = "Subdivision codes, as MarketRegion.states.") List<String> states,
        List<SubdivisionView> subdivisions,
        @Schema(description = "Branch codes in this region, in code order.") List<String> storeIds) {

    /** A US state or UK region. */
    @Schema(name = "Subdivision")
    record SubdivisionView(@Schema(example = "TX") String code, @Schema(example = "Texas") String name) {
    }
}
