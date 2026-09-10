package com.aatlas.analytics.internal.fixtures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One US branch from {@code seed/stores.json}'s {@code US} array - the frontend's
 * {@code StoreItem} (TENANTS_US in {@code mock/catalog.ts}) plus {@code regionKey}, the
 * market region {@code logistics.ts}'s lanes are landed against.
 *
 * <p>Read directly from the seed file rather than {@code catalog}'s {@code stores} table for
 * the same reason as {@link ProductFixture} - see the module javadoc.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoreFixture(
        @JsonProperty("store_id") String storeId,
        @JsonProperty("legal_name") String legalName,
        @JsonProperty("msa_name") String msaName,
        String state,
        Double rpp,
        String segment,
        String country,
        String regionKey) {
}
