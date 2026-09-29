package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One line of the history: what was stored, by whom, when.
 *
 * @param action {@code set} or {@code reset}
 * @param snapshot the override map as stored after the change; {@code {}} for a reset
 * @param changedBy the user's id; null once that user has been deleted
 * @param changedByRole the seat they held at the time, which is what justified the change
 */
@Schema(name = "PricingModelHistoryEntry")
record PricingModelHistoryView(
        UUID id,
        String action,
        Map<String, PricingModel.Setting> snapshot,
        UUID changedBy,
        String changedByRole,
        Instant changedAt) {

    static PricingModelHistoryView of(PricingModelHistoryEntity row) {
        return new PricingModelHistoryView(row.getId(), row.getAction(),
                PricingModel.Config.of(row.getSnapshot()).overrides(), row.getChangedBy(), row.getChangedByRole(),
                row.getChangedAt());
    }
}
