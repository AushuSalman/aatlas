package com.aatlas.ingest.internal;

import java.util.List;
import java.util.Map;

/**
 * What a load actually did, as opposed to what validation predicted it would do.
 *
 * <p>The four counters every kind reports are fields; the kind-specific ones
 * ({@code customersCreated}, {@code pricesWritten}, {@code observationsWritten}, ...) travel in
 * {@code summary}, which is stored verbatim on {@code import_batches.commit_summary} and
 * rendered verbatim by the connect screen.
 */
record LoadResult(
        int loadedRows,
        int productsCreated,
        int branchesCreated,
        List<String> branchesNeedingRegion,
        int distinctSuppliers,
        Map<String, Object> summary) {

    LoadResult {
        branchesNeedingRegion = branchesNeedingRegion == null ? List.of() : List.copyOf(branchesNeedingRegion);
        summary = summary == null ? Map.of() : Map.copyOf(summary);
    }
}
