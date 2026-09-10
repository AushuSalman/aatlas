package com.aatlas.suppliers.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row of {@code seed/suppliers.json}: the Buy screen's {@code SupplierRecord}, the rated
 * {@code SupplierProfile}, and the seeded {@code CommercialTerms} for one panel supplier.
 *
 * <p>{@code record} is a restricted identifier in Java (legal as an identifier, but reads
 * oddly next to the {@code record} keyword), so the field is named {@code supplierRecord}
 * and mapped back to the JSON key with {@link JsonProperty}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SeedSupplierEntry(
        @JsonProperty("record") SeedSupplierRecord supplierRecord,
        SupplierProfileView profile,
        CommercialTerms terms) {
}
