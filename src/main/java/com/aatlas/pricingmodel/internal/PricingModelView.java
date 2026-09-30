package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One side of the model for one tenant: that side's registry (groups and parameters),
 * every key's effective setting, the hand-set overrides and the learned values that
 * produced it, and who last saved.
 *
 * <p>Side-scoped on purpose. The sell model and the buying model share one stored row,
 * but each settings screen shows one of them, so the view carries only that side's keys:
 * a sell screen never lists a {@code buy.} parameter and its summary counts only the
 * sell toggles. {@code updatedAt} and {@code updatedBy} are the row's - a save on either
 * side moves them; a retune moves {@code learnedAt} instead.
 *
 * @param side         {@code sell} or {@code buy}
 * @param activePreset the preset the hand-set overrides are exactly, or {@code custom}; learned values do not count
 * @param settings     every parameter key of this side with its effective setting - what the chain runs with:
 *                     defaults, then learned (while {@code autoTune} is on), then hand-set
 * @param overrides    only what the tenant set by hand on this side, differing from the defaults - what is stored for it
 * @param learned      what the tuner learned on this side, same shape as {@code overrides}; shown even when
 *                     {@code autoTune} is off or a hand-set value shadows it
 * @param learnedNotes the reason and evidence behind each key of {@code learned}
 * @param learnedAt    when the tuner last ran for this tenant; absent until it has
 * @param autoTune     whether this side's "retune from your results" toggle is on, judged from the hand-set model
 * @param updatedAt    absent until the row exists: a save, a reset or the first retune
 */
@Schema(name = "PricingModel",
        description = "One side's registry and presets, with the tenant's effective settings, their hand-set overrides "
                + "and what the model learned for itself.")
record PricingModelView(
        @Schema(allowableValues = {"sell", "buy"}) String side,
        List<GroupView> groups,
        List<ParameterView> parameters,
        List<PresetView> presets,
        String activePreset,
        Map<String, PricingModel.Setting> settings,
        Map<String, PricingModel.Setting> overrides,
        Map<String, PricingModel.Setting> learned,
        Map<String, LearnedNote> learnedNotes,
        Instant learnedAt,
        boolean autoTune,
        Summary summary,
        Instant updatedAt,
        UUID updatedBy) {

    /** How many of this side's toggles are on, out of how many. */
    @Schema(name = "PricingModelSummary")
    record Summary(int on, int total) {
    }

    /** A one-click preset: the overrides it applies on top of the defaults. */
    @Schema(name = "PricingModelPreset")
    record PresetView(String key, String label, String blurb, Map<String, PricingModel.Setting> settings) {

        static PresetView of(PricingModel.Preset p) {
            return new PresetView(p.key(), p.label(), p.blurb(), p.settings());
        }
    }

    /**
     * @param overrides the stored hand-set map, both sides
     * @param learned   the stored learned map, both sides
     * @param notes     the stored notes, both sides
     */
    static PricingModelView of(PricingModel.Side side, Map<String, PricingModel.Setting> overrides,
            Map<String, PricingModel.Setting> learned, Map<String, LearnedNote> notes, Instant learnedAt,
            Instant updatedAt, UUID updatedBy) {
        PricingModel.Config hand = PricingModel.Config.of(overrides);
        PricingModel.Config effective = PricingModel.Config.layered(learned, overrides);
        int[] count = effective.toggleCount(side);
        return new PricingModelView(
                side.key(),
                PricingModel.Group.of(side).stream().map(GroupView::of).toList(),
                PricingModel.registry(side).stream().map(ParameterView::of).toList(),
                PricingModel.presets(side).stream().map(PresetView::of).toList(),
                hand.activePreset(side),
                effective.effective(side),
                hand.overrides(side),
                PricingModelEntity.sideOf(learned, side),
                PricingModelEntity.sideOf(notes, side),
                learnedAt,
                hand.on(LearnedView.autoTuneKey(side)),
                new Summary(count[0], count[1]),
                updatedAt,
                updatedBy);
    }

    static PricingModelView of(PricingModel.Side side, PricingModelEntity row) {
        return of(side, row.getSettings(), row.getLearned(), row.getLearnedNotes(), row.getLearnedAt(),
                row.getUpdatedAt(), row.getUpdatedBy());
    }

    static PricingModelView defaults(PricingModel.Side side) {
        return of(side, Map.of(), Map.of(), Map.of(), null, null, null);
    }
}
