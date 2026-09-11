package com.aatlas.ingest;

import com.aatlas.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A file's rows are now in {@code sales_transactions}.
 *
 * <p>The event the engines wait for. Until a tenant has transaction history there is nothing
 * to price from, so this is what triggers the first pricing run - and why the import does
 * not try to compute anything itself. It states what landed and lets the workers decide what
 * that invalidates.
 *
 * @param batchId the import that produced the rows
 * @param loadedRows how many landed
 * @param earliest first transaction date in the file, so a worker knows which months moved
 * @param latest last transaction date in the file
 */
public record ImportCommitted(
        UUID tenantId,
        UUID batchId,
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
