package com.aatlas.analytics.internal.fixtures;

/**
 * One resolved inbound lane: origin country in, destination region out. Ported from
 * {@code logistics.ts}'s {@code Lane} - see {@link Fixtures#laneFor}.
 */
public record Lane(
        String originCountry,
        String regionKey,
        String regionLabel,
        double freightPct,
        double dutyPct,
        String dutyNote,
        int transitDays) {
}
