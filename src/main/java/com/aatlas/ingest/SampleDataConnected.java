package com.aatlas.ingest;

import com.aatlas.common.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant now has the sample catalogue. Committed with the data-source row, published
 * after it, so an engine that reacts (the first recommendation run, the overview
 * snapshot) can never see the event without the rows.
 */
public record SampleDataConnected(UUID tenantId, UUID dataSourceId, Instant occurredAt) implements DomainEvent {

    public static final String TYPE = "ingest.sample-data.connected";

    @Override
    public String type() {
        return TYPE;
    }
}
