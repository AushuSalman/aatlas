package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The whole model for one tenant: the registry (groups and parameters), every key's
 * effective setting, the overrides that produced it, and who last saved.
 *
 * @param settings every parameter key with its effective setting - what the chain runs with
 * @param overrides only what differs from the defaults - what is stored
 * @param updatedAt absent while a tenant is still on the defaults and has never saved
 */
@Schema(name = "PricingModel", description = "The registry, the tenant's effective settings and their overrides.")
record PricingModelView(
        List<GroupView> groups,
        List<ParameterView> parameters,
        Map<String, PricingModel.Setting> settings,
        Map<String, PricingModel.Setting> overrides,
        Summary summary,
        Instant updatedAt,
        UUID updatedBy) {

    /** How many toggles are on, out of how many. */
    @Schema(name = "PricingModelSummary")
    record Summary(int on, int total) {
    }

    static PricingModelView of(PricingModel.Config config, Instant updatedAt, UUID updatedBy) {
        int[] count = config.toggleCount();
        return new PricingModelView(
                Arrays.stream(PricingModel.Group.values()).map(GroupView::of).toList(),
                PricingModel.registry().stream().map(ParameterView::of).toList(),
                config.effective(),
                config.overrides(),
                new Summary(count[0], count[1]),
                updatedAt,
                updatedBy);
    }

    static PricingModelView of(PricingModelEntity row) {
        return of(PricingModel.Config.of(row.getSettings()), row.getUpdatedAt(), row.getUpdatedBy());
    }

    static PricingModelView defaults() {
        return of(PricingModel.Config.defaults(), null, null);
    }
}
