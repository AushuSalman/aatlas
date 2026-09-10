package com.aatlas.buy;

/** One line of the "how we got this price" derivation. The frontend's {@code CalcStep}. */
public record CalcStep(String label, String value, String note, String kind) {
}
