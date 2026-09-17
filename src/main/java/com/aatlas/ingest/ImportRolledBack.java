package com.aatlas.ingest;

import com.aatlas.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A committed import was removed again, with everything it loaded and everything it created
 * that nothing else referenced.
 *
 * @param kind {@code sales | purchases | products | competitor_prices}
 */
public record ImportRolledBack(UUID tenantId, UUID batchId, String kind, Instant occurredAt) implements DomainEvent {

    @Override
    public String type() {
        return "ingest.import.rolled-back";
    }
}
