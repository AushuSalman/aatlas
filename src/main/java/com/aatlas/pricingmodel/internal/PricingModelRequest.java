package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * The settings as one side's Settings screen posts them: parameter key to setting. A toggle
 * carries {@code on}, a number carries {@code value}; a setting equal to the default is
 * accepted and simply not stored. Keys of the side left out are left at their default -
 * the screen posts the whole side, so what is sent is what is meant. The other side's
 * stored overrides are untouched by the save.
 *
 * <p>An unknown key, a key that belongs to the other side, a toggle with a {@code value},
 * a number with an {@code on} or a number outside its range is a 400 with a message per key.
 */
@Schema(name = "PricingModelRequest", description = "Parameter key to {on} for a toggle or {value} for a number.")
record PricingModelRequest(
        @Schema(example = "{\"elasticity\":{\"on\":false},\"demand.maxMove\":{\"value\":5}}")
                @NotNull(message = "Send the settings map.")
                Map<String, PricingModel.Setting> settings) {
}
