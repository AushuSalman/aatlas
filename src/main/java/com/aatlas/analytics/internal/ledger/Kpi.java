package com.aatlas.analytics.internal.ledger;

import java.util.List;

/** A headline figure with the context that makes it readable. Ports {@code procurement.ts}'s {@code Kpi}. */
public record Kpi(double value, double previous, Double deltaPct, List<Double> series) {
}
