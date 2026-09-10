package com.aatlas.analytics.internal.ledger;

import java.util.List;

/** One slice of a spend breakdown. Ports {@code procurement.ts}'s {@code MixSlice}. */
public record MixSlice(
        String key,
        String label,
        double value,
        double sharePct,
        double prevValue,
        double prevSharePct,
        Double changePts,
        List<Double> series,
        int colorIndex) {
}
