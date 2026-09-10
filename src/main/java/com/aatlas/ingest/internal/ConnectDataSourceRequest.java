package com.aatlas.ingest.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Connect a source. For {@code sample} only {@code kind} matters; for {@code erp} and
 * {@code warehouse} a label is required and the rest is recorded for the connector.
 * {@code csv} is not accepted here: uploads go through {@code POST /api/v1/imports}.
 */
@Schema(name = "ConnectDataSourceRequest")
record ConnectDataSourceRequest(
        @Schema(example = "sample", allowableValues = {"sample", "erp", "warehouse"})
                @NotNull(message = "Say which kind of source this is.")
                DataSourceKind kind,

        @Schema(description = "Shown in the UI. Required for erp and warehouse.", example = "Epicor Prophet 21")
                @Size(max = 120, message = "That label is too long.")
                String label,

        @Schema(description = "One line of detail, e.g. \"Nightly at 02:00\".", example = "Nightly at 02:00")
                @Size(max = 400, message = "That detail is too long.")
                String detail,

        @Schema(description = "Connector settings. Stored, never returned.")
                Map<String, Object> config,

        @Schema(description = "Sync schedule, as a cron expression or a phrase the connector understands.",
                        example = "0 2 * * *")
                @Size(max = 120, message = "That schedule is too long.")
                String schedule) {
}
