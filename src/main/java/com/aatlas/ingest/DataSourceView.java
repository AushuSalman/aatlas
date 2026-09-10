package com.aatlas.ingest;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * A connected source as the frontend's {@code DataSource} reads it, plus our id, its
 * sync state and when it last synced. The connector's {@code config} is never included.
 */
@Schema(name = "DataSource")
public record DataSourceView(
        UUID id,
        @Schema(allowableValues = {"csv", "erp", "warehouse", "sample"}) String kind,
        @Schema(example = "Sample dataset") String label,
        @Schema(example = "Demo account — seeded history") String detail,
        @Schema(allowableValues = {"pending", "connected", "syncing", "error"}) String status,
        Instant connectedAt,
        Instant lastSyncAt) {
}
