package com.aatlas.analytics.internal.ledger;

/** Ports {@code procurement.ts}'s {@code TimelinePoint}. */
public record TimelinePoint(
        Bucket bucket,
        double spend,
        double atTarget,
        double excess,
        double saved,
        double leaked,
        int orders,
        int units,
        double avgUnitCost,
        double captureRatePct,
        double onTimePct) {
}
