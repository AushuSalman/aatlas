package com.aatlas.ingest;

import com.aatlas.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A file's rows are now in the fact tables.
 *
 * <p>The event the engines wait for. Until a tenant has history there is nothing to price
 * from, so this is what triggers the first pricing run - and why the import does not try to
 * compute anything itself. It states what landed and lets the workers decide what that
 * invalidates.
 *
 * @param batchId the import that produced the rows
 * @param kind {@code sales | purchases | products | competitor_prices}
 * @param source {@code upload} for the tenant's own file, {@code sample} for the Hardin sample
 * @param loadedRows how many landed
 * @param earliest first transaction date in the file, so a worker knows which months moved
 * @param latest last transaction date in the file
 */
public record ImportCommitted(
        UUID tenantId,
        UUID batchId,
        String kind,
        String source,
        int loadedRows,
        java.time.LocalDate earliest,
        java.time.LocalDate latest,
        Instant occurredAt)
        implements DomainEvent {

    @Override
    public String type() {
        return "ingest.import.committed";
    }
}
