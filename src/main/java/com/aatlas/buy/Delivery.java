package com.aatlas.buy;

/** The odds of arriving by a required date, on one route. The frontend's {@code Delivery}. */
public record Delivery(
        int expectedDays,
        int rangeLow,
        int rangeHigh,
        int requiredDays,
        int bufferDays,
        double onTimePct,
        double lateProbability) {
}
