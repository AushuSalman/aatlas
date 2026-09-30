package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import com.aatlas.history.PricingModel.Side;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the model tuner learned for one side, ready for the screen: each learned setting
 * with its label and unit, what it was before, what it is now, and why in plain words.
 *
 * @param side      {@code sell} or {@code buy}
 * @param learnedAt when the tuner last ran for this tenant (either side); absent until it has
 * @param autoTune  whether this side's "retune from your results" toggle is on in the hand-set model,
 *                  which is what decides whether the entries below are in use
 * @param entries   this side's learned settings, in registry order; empty when nothing passed its evidence gate
 */
@Schema(name = "PricingModelLearned", description = "What the model tuner learned for one side, with reasons.")
record LearnedView(
        @Schema(allowableValues = {"sell", "buy"}) String side,
        Instant learnedAt,
        boolean autoTune,
        List<Entry> entries) {

    /**
     * @param from     the value before this learned one: the previous learned value, else the registry default
     * @param to       the learned value
     * @param reason   one plain sentence; absent only for a value stored without a note
     * @param evidence decisions or measured outcomes it rests on; absent only for a value stored without a note
     */
    @Schema(name = "PricingModelLearnedEntry")
    record Entry(String key, String label, String unit, BigDecimal from, BigDecimal to, String reason,
            Integer evidence) {
    }

    static LearnedView of(Side side, PricingModelEntity row) {
        if (row == null) {
            return of(side, Map.of(), Map.of(), Map.of(), null);
        }
        return of(side, row.getSettings(), row.getLearned(), row.getLearnedNotes(), row.getLearnedAt());
    }

    static LearnedView of(Side side, Map<String, PricingModel.Setting> overrides,
            Map<String, PricingModel.Setting> learned, Map<String, LearnedNote> notes, Instant learnedAt) {
        boolean autoTune = PricingModel.Config.of(overrides).on(autoTuneKey(side));
        List<Entry> entries = new ArrayList<>();
        for (PricingModel.Parameter p : PricingModel.registry(side)) {
            PricingModel.Setting s = learned.get(p.key());
            if (s == null || s.value() == null) {
                continue; // only numbers are learned
            }
            LearnedNote note = notes.get(p.key());
            entries.add(new Entry(p.key(), p.label(), p.unit(),
                    note != null && note.from() != null ? note.from() : p.defaultValue(),
                    s.value(),
                    note == null ? null : note.reason(),
                    note == null ? null : note.evidence()));
        }
        return new LearnedView(side.key(), learnedAt, autoTune, entries);
    }

    /** The toggle that says whether a side's learned values are in use. */
    static String autoTuneKey(Side side) {
        return side == Side.BUY ? PricingModel.BUY_LEARNING_AUTO_TUNE : PricingModel.LEARNING_AUTO_TUNE;
    }
}
