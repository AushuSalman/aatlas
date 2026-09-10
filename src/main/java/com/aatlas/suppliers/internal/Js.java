package com.aatlas.suppliers.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * JavaScript number semantics, for the ported engines.
 *
 * <p>The supplier engines are ports of TypeScript that the frontend still runs, and every
 * figure they produce has to match it: a rating of 4.5 here and 4.4 there is a bug report.
 * Most arithmetic is identical between the two languages because both use IEEE-754 doubles,
 * but a handful of library calls are not, and those are collected here so the engine code
 * reads like the TypeScript it mirrors.
 */
final class Js {

    private Js() {
    }

    /** {@code Math.round(n * 10) / 10}. Java's {@code Math.round} rounds half up like JavaScript's. */
    static double round1(double n) {
        return Math.round(n * 10) / 10.0;
    }

    static double round2(double n) {
        return Math.round(n * 100) / 100.0;
    }

    static double round3(double n) {
        return Math.round(n * 1000) / 1000.0;
    }

    static double clamp(double n, double lo, double hi) {
        return Math.max(lo, Math.min(hi, n));
    }

    /**
     * {@code Number.prototype.toFixed}.
     *
     * <p>Not {@code String.format("%.1f")}: Java's formatter rounds the shortest decimal
     * representation of the double, so {@code 1.005} formats as {@code 1.01}, while
     * JavaScript rounds the exact binary value and gives {@code 1.00}. {@code new BigDecimal(double)}
     * is that exact value, and HALF_UP on it is what the specification describes.
     */
    static String toFixed(double value, int digits) {
        return new BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * A number in a template literal: integers print without a decimal point, everything
     * else as its shortest round-trip form - {@code 2}, {@code 1.2}, {@code 1.03}.
     */
    static String num(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** {@code n.toLocaleString('en-US')} for an integer: {@code 5,431}. */
    static String localeInt(long value) {
        return String.format(Locale.US, "%,d", value);
    }
}
