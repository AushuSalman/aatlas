package com.aatlas.analytics.internal.ledger;

/** On-time record for one bucket. Ports {@code procurement.ts}'s {@code DeliveryPoint}. */
public record DeliveryPoint(Bucket bucket, double onTimePct, int late, int onTimeCount, int received, int avgLeadDays) {
}
