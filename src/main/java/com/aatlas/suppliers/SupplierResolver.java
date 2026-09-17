package com.aatlas.suppliers;

import java.util.Optional;
import java.util.UUID;

/**
 * Finding or creating a supplier from what a purchase-order or product file says about it,
 * which is a name and perhaps a country. The one way another module adds to the panel.
 *
 * <p>A supplier created here carries only what the file said. Every performance figure
 * stays {@code null} - "not provided" - until a person or a supplier file supplies it, so no
 * formula ever scores a placeholder.
 */
public interface SupplierResolver {

    /** Matches {@code supplier_key}, {@code vendor_code} or the name, case- and padding-insensitively. */
    Optional<SupplierRef> find(UUID tenantId, String codeOrName);

    SupplierRef create(UUID tenantId, String name, String countryRaw, String source, UUID importBatchIdOrNull);

    /** The canonical spelling of a country the platform prices lanes for, else the input stripped. */
    String canonicalCountry(String raw);

    /** Whether the platform has a shipping lane (freight, duty, transit) for this canonical country. */
    boolean hasLane(String canonicalCountry);

    record SupplierRef(UUID id, String supplierKey, String name, String country) {
    }
}
