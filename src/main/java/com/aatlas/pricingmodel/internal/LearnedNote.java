package com.aatlas.pricingmodel.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * Why the tuner set one learned value, in the customer's words, with the evidence behind
 * it. Stored per key in {@code pricing_model_settings.learned_notes} and shown beside the
 * setting on the screen.
 *
 * @param reason   one plain sentence, e.g. "Measured across 12 applied prices: a 1% price rise cost about 1.4% of sales."
 * @param evidence how many decisions or measured outcomes it rests on
 * @param from     what the value was before this run: the previous learned value, else the registry default
 * @param to       the learned value, already clamped to the parameter's range
 */
@Schema(name = "PricingModelLearnedNote", description = "The reason and evidence behind one learned setting.")
public record LearnedNote(String reason, int evidence, BigDecimal from, BigDecimal to) {
}
