package com.aatlas.buy;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One link in the "why this cost" chain. {@code price} is the running price after this step.
 *
 * <p>{@code effect} is genuinely nullable in the frontend (an informational step carries
 * {@code effect: null}, not an absent field) - {@code @JsonInclude(ALWAYS)} overrides the
 * application's default {@code non_null} inclusion for this one field so a null serialises
 * as {@code "effect":null}, matching {@code JSON.stringify} rather than dropping the key.
 */
public record ChainStep(
        String key, String label, String value, @JsonInclude(JsonInclude.Include.ALWAYS) String effect,
        String note, double price) {
}
