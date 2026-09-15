package com.aatlas.catalog.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * A branch as the frontend's {@code StoreItem} reads it, plus country, market region and
 * map position.
 *
 * <p>The snake_case keys are deliberate and must stay: {@code StoreItem} in
 * {@code src/lib/types.ts} was ported from the original service with its field names
 * intact, and every screen reads {@code store_id}, {@code legal_name}, {@code msa_name}
 * and {@code item_count} as they are. The additions ({@code country}, {@code regionKey},
 * {@code map}, {@code active}, {@code source}) are camelCase like the rest of the API.
 *
 * <p>Every key here is one {@link CreateStoreRequest} and {@link PatchStoreRequest}
 * accept under the same name, so a client can send back what it read.
 */
@Schema(name = "Store", description = "A branch. Snake_case keys match the frontend's StoreItem verbatim.")
record StoreView(
        @Schema(description = "Our id. Either this or store_id is accepted by GET /stores/{id}.") UUID id,
        @JsonProperty("store_id") @Schema(example = "100959") String storeId,
        @JsonProperty("company_number") @Schema(example = "00104") String companyNumber,
        @JsonProperty("legal_name") @Schema(example = "Dallas Branch") String legalName,
        @Schema(description = "US state or UK region code.", example = "TX") String state,
        @JsonProperty("msa_name") @Schema(example = "Dallas-Fort Worth-Arlington") String msaName,
        @Schema(description = "Regional price parity, 100 = national average. Absent = priced nationally.")
                BigDecimal rpp,
        Integer txns,
        @JsonProperty("item_count") Integer itemCount,
        @Schema(allowableValues = {"regular", "occasional"}) String segment,
        @Schema(example = "US") String country,
        @Schema(description = "unassigned means nobody has placed this branch yet; it is kept out of "
                        + "every regional rollup until they do.",
                        allowableValues = {"south", "west", "north", "east", "unassigned"})
                String regionKey,
        MapPoint map,
        @Schema(description = "A deactivated branch stays in the catalogue and its history, and is "
                        + "filtered out of the pickers with ?active=true.")
                boolean active,
        @Schema(description = "How the branch got here. Read-only.",
                        allowableValues = {"manual", "import", "erp", "sample"})
                String source) {

    /** Where the dot sits on the 960x520 country map, and which side its label goes. */
    @Schema(name = "MapPoint")
    record MapPoint(Integer x, Integer y, @Schema(allowableValues = {"start", "end"}) String anchor) {
    }

    static StoreView of(StoreEntity store) {
        MapPoint map = store.getMapX() == null && store.getMapY() == null
                ? null
                : new MapPoint(store.getMapX(), store.getMapY(), store.getMapAnchor());
        return new StoreView(
                store.getId(),
                store.getStoreCode(),
                store.getCompanyNumber(),
                store.getLegalName(),
                store.getSubdivisionCode(),
                store.getMsaName(),
                store.getRpp(),
                store.getTxns(),
                store.getItemCount(),
                store.getSegment(),
                store.getCountry(),
                store.getRegionKey(),
                map,
                store.isActive(),
                store.getSource());
    }
}
