package com.aatlas.analytics.internal.fixtures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One entry of {@code logistics.json}'s {@code origins} map - ported from {@code logistics.ts}'s {@code Origin}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OriginFixture(
        String entry,
        String mode,
        String gateway,
        double inboundPct,
        int inboundDays,
        double dutyPct,
        String dutyNote) {
}
