package com.aatlas.sell.internal.engine;

/**
 * {@code Math.round(n * 10^k) / 10^k}, exactly as every engine in {@code src/lib} rounds.
 *
 * <p>JavaScript's {@code Math.round} rounds half toward positive infinity ({@code
 * Math.round(-2.5) === -2}), which Java's {@code Math.round(double)} matches bit for bit;
 * {@code RoundingMode.HALF_UP} on a {@code BigDecimal} does not for negatives. See {@code
 * golden/README.md}.
 */
final class Round {

    private Round() {
    }

    static double round2(double n) {
        return Math.round(n * 100.0) / 100.0;
    }

    static double round1(double n) {
        return Math.round(n * 10.0) / 10.0;
    }

    static double round0(double n) {
        return Math.round(n);
    }

    static double clamp(double n, double lo, double hi) {
        return Math.max(lo, Math.min(hi, n));
    }
}
