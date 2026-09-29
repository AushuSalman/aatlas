package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * One entry of the registry, as the settings screen renders it.
 *
 * @param type {@code toggle} or {@code number}
 * @param defaultValue the number's default; absent for a toggle
 * @param min the number's floor, inclusive; absent for a toggle
 * @param max the number's ceiling, inclusive; absent for a toggle
 * @param unit what the number is in ("%", "days", "× cost", or "" for a bare ratio)
 * @param parent the toggle this parameter belongs to; absent for a top-level one
 * @param doc the section of the pricing walkthrough it comes from
 */
@Schema(name = "PricingModelParameter", description = "One step or knob of the pricing model, with its default and range.")
record ParameterView(
        String key,
        String group,
        String type,
        String label,
        String description,
        boolean defaultOn,
        BigDecimal defaultValue,
        BigDecimal min,
        BigDecimal max,
        String unit,
        String parent,
        String doc) {

    static ParameterView of(PricingModel.Parameter p) {
        return new ParameterView(p.key(), p.group().key(), p.type().name().toLowerCase(Locale.ROOT), p.label(),
                p.description(), p.defaultOn(), p.defaultValue(), p.min(), p.max(), p.unit(), p.parent(), p.doc());
    }
}
