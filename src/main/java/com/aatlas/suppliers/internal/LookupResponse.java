package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** What the lookup found, plus the id to reference it by when adding it to the panel. */
@Schema(name = "SupplierLookupResult")
record LookupResponse(
        String query,
        SupplierProfileView profile,
        List<LookupSource> sources,
        List<String> watchOuts,
        String recommendation,
        UUID lookupId) {
}
