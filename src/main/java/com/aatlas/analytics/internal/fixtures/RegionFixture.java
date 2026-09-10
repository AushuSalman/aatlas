package com.aatlas.analytics.internal.fixtures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** One row of {@code logistics.json}'s {@code regions} - ported from {@code logistics.ts}'s {@code Region}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegionFixture(
        String key,
        String label,
        List<String> states,
        Map<String, Double> inlandPct,
        Map<String, Integer> inlandDays) {
}
