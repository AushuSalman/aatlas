package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One side of the model for one tenant: that side's registry (groups and parameters),
 * every key's effective setting, the overrides that produced it, and who last saved.
 *
 * <p>Side-scoped on purpose. The sell model and the buying model share one stored row,
 * but each settings screen shows one of them, so the view carries only that side's keys:
 * a sell screen never lists a {@code buy.} parameter and its summary counts only the
 * sell toggles. {@code updatedAt} and {@code updatedBy} are the row's - a save on either
 * side moves them.
 *
 * @param side {@code sell} or {@code buy}
 * @param settings every parameter key of this side with its effective setting - what the chain runs with
 * @param overrides only what differs from the defaults on this side - what is stored for it
 * @param updatedAt absent while a tenant is still on the defaults and has never saved either side
 */
@Schema(name = "PricingModel",
        description = "One side's registry and presets, with the tenant's effective settings and their overrides.")
record PricingModelView(
        @Schema(allowableValues = {"sell", "buy"}) String side,
        List<GroupView> groups,
        List<ParameterView> parameters,
        List<PresetView> presets,
        String activePreset,
        Map<String, PricingModel.Setting> settings,
        Map<String, PricingModel.Setting> overrides,
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

    static PricingModelView of(PricingModel.Side side, PricingModel.Config config, Instant updatedAt, UUID updatedBy) {
        int[] count = config.toggleCount(side);
        return new PricingModelView(
                side.key(),
                PricingModel.Group.of(side).stream().map(GroupView::of).toList(),
                PricingModel.registry(side).stream().map(ParameterView::of).toList(),
                PricingModel.presets(side).stream().map(PresetView::of).toList(),
                config.activePreset(side),
                config.effective(side),
                config.overrides(side),
                new Summary(count[0], count[1]),
                updatedAt,
                updatedBy);
    }

    static PricingModelView of(PricingModel.Side side, PricingModelEntity row) {
        return of(side, PricingModel.Config.of(row.getSettings()), row.getUpdatedAt(), row.getUpdatedBy());
    }

    static PricingModelView defaults(PricingModel.Side side) {
        return of(side, PricingModel.Config.defaults(), null, null);
    }
}
