package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One line of the history: what was stored, by whom, when, seen from one side.
 *
 * <p>A row records the whole stored map, both sides, because one save writes one row.
 * Shown to a side's screen it is filtered to that side's keys, so the sell history reads
 * as the sell model's own timeline: a save on the buy side appears as an entry whose sell
 * snapshot did not move.
 *
 * @param side {@code sell} or {@code buy}: which side's keys the snapshot is filtered to
 * @param action {@code set} or {@code reset}
 * @param snapshot this side's overrides as stored after the change; {@code {}} once this side is on the defaults
 * @param changedBy the user's id; null once that user has been deleted
 * @param changedByRole the seat they held at the time, which is what justified the change
 */
@Schema(name = "PricingModelHistoryEntry")
record PricingModelHistoryView(
        UUID id,
        @Schema(allowableValues = {"sell", "buy"}) String side,
        String action,
        Map<String, PricingModel.Setting> snapshot,
        UUID changedBy,
        String changedByRole,
        Instant changedAt) {

    static PricingModelHistoryView of(PricingModel.Side side, PricingModelHistoryEntity row) {
        return new PricingModelHistoryView(row.getId(), side.key(), row.getAction(),
                PricingModel.Config.of(row.getSnapshot()).overrides(side), row.getChangedBy(), row.getChangedByRole(),
                row.getChangedAt());
    }
}
