package com.aatlas.ingest;

import com.aatlas.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * The Hardin sample was removed from a tenant: every sample batch rolled back, every
 * unreferenced sample product, branch, customer and supplier deleted, the source row gone.
 * The workspace is back at onboarding.
 */
public record SampleDataRemoved(UUID tenantId, Instant occurredAt) implements DomainEvent {

    @Override
    public String type() {
        return "ingest.sample-data.removed";
    }
}
