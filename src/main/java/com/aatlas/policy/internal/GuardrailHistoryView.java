package com.aatlas.policy.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of the history: what was saved, by whom, when.
 *
 * @param action {@code set} or {@code reset}
 * @param changedBy the user's id; null once that user has been deleted
 * @param changedByRole the seat they held at the time, which is what justified the change
 */
@Schema(name = "GuardrailHistoryEntry")
record GuardrailHistoryView(
        UUID id,
        String action,
        GuardrailValues guardrails,
        UUID changedBy,
        String changedByRole,
        Instant changedAt) {

    static GuardrailHistoryView of(GuardrailHistoryEntity row) {
        return new GuardrailHistoryView(row.getId(), row.getAction(), row.getSnapshot().normalised(),
                row.getChangedBy(), row.getChangedByRole(), row.getChangedAt());
    }
}
