package com.aatlas.ingest.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Reassign columns and revalidate.
 *
 * <p>The whole mapping is sent, not a patch. Column choices are interdependent - claiming a
 * column for one field takes it from another - so a partial update would need the server to
 * reason about what the user could see, and what they could see is the whole picker.
 *
 * @param mapping field key to zero-based column index, e.g. {@code {"item": 0, "qty": 3}}.
 *     A field left out has no column, which is how a wrongly detected one is cleared.
 */
@Schema(name = "UpdateMappingRequest", description = "Reassign CSV columns to fields and revalidate.")
record UpdateMappingRequest(
        @Schema(example = "{\"item\": 0, \"date\": 2, \"qty\": 3, \"price\": 4}")
                @NotNull(message = "A mapping is required.")
                Map<String, Integer> mapping) {
}
