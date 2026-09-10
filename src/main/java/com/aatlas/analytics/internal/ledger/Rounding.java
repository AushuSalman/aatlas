package com.aatlas.analytics.internal.ledger;

/**
 * {@code Math.round(n * 10^k) / 10^k}, exactly as every frontend engine rounds. JavaScript's
 * {@code Math.round} rounds half toward +infinity ({@code Math.round(-2.5) === -2}), which
 * Java's {@code Math.round(double)} matches and {@code RoundingMode.HALF_UP} does not for
 * negative numbers - see {@code golden/README.md}.
 */
final class Rounding {

    private Rounding() {
    }

    static double round2(double n) {
        return Math.round(n * 100.0) / 100.0;
    }

    static double round4(double n) {
        return Math.round(n * 10000.0) / 10000.0;
    }
}
