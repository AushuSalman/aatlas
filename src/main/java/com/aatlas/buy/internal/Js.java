package com.aatlas.buy.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * JavaScript-shaped string formatting, ported by hand because the golden files compare the
 * Java port's prose to the TypeScript prototype's byte for byte.
 *
 * <p>Money is USD only here - no currency conversion in the engine (that is
 * {@code platform/money.ts}'s job on the frontend, per the wave-2 brief) - but the exact
 * grouping, decimal places and sign character the frontend's {@code fmtMoney}/{@code
 * fmtCompact} use with the trading currency defaulted to USD are reproduced, because those
 * are the strings the golden fixtures were generated with (locale 'US', currency 'USD' -
 * see {@code golden/README.md}).
 *
 * <p>Two characters that are not ASCII: a negative amount is written with U+2212 MINUS
 * SIGN, not a hyphen, and a range uses U+2013 EN DASH where the source literal calls for
 * one (some call sites use a plain " - " instead - each is copied from the actual
 * TypeScript template literal, not standardised here).
 */
public final class Js {

    public static final String MINUS = "−";
    public static final String EN_DASH = "–";

    private Js() {
    }

    /** {@code Math.round(n * 100) / 100}. Java's {@code Math.round(double)} rounds half toward
     * +infinity, exactly like JavaScript's {@code Math.round}; {@code RoundingMode.HALF_UP}
     * does not agree with it for negatives, so it is deliberately not used here. */
    public static double round2(double n) {
        return Math.round(n * 100.0) / 100.0;
    }

    public static double round1(double n) {
        return Math.round(n * 10.0) / 10.0;
    }

    public static double clamp(double n, double lo, double hi) {
        return Math.max(lo, Math.min(hi, n));
    }

    /**
     * JavaScript's default {@code Number -> String}: no trailing {@code .0}, otherwise as-is.
     * Every caller in this engine passes an already-rounded, finite percentage or amount, so
     * infinity is not guarded against - {@code Double.isInfinite} would depend on {@code
     * java.lang.Double}, which this package may not (see {@code toFixed} below).
     */
    public static String num(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /**
     * {@code n.toFixed(digits)}. Via {@link BigDecimal}, not {@code String.format("%.Nf", n)}:
     * the varargs {@code Object...} of {@code String.format} would autobox the primitive
     * {@code double} into {@code java.lang.Double}, which {@code
     * ArchitectureRulesTest.noFloatingPointMoney} forbids this package depending on.
     */
    public static String toFixed(double n, int digits) {
        return BigDecimal.valueOf(n).setScale(digits, RoundingMode.HALF_UP).toPlainString();
    }

    /** {@code n.toLocaleString('en-US')} for an integer count. */
    public static String localeInt(long n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    /** {@code fmtMoney(usd)}: {@code $1,234.56}, two decimals, en-US grouping, U+2212 if negative. */
    public static String fmtMoney(double usd) {
        return fmtMoney(usd, 2);
    }

    public static String fmtMoney(double usd, int dp) {
        boolean negative = usd < 0;
        double abs = Math.abs(usd);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(dp);
        nf.setMaximumFractionDigits(dp);
        return (negative ? MINUS : "") + "$" + nf.format(abs);
    }

    /** {@code fmtCompact(usd)}: {@code $1.2k} / {@code $3.4M} / {@code $1.20B} style. */
    public static String fmtCompact(double usd) {
        boolean negative = usd < 0;
        double abs = Math.abs(usd);
        String sign = negative ? MINUS : "";
        if (abs >= 1_000_000_000d) {
            double v = abs / 1_000_000_000d;
            return sign + "$" + toFixed(v, abs >= 10_000_000_000d ? 1 : 2) + "B";
        }
        if (abs >= 1_000_000d) {
            double v = abs / 1_000_000d;
            return sign + "$" + toFixed(v, abs >= 10_000_000d ? 1 : 2) + "M";
        }
        if (abs >= 1_000d) {
            double v = abs / 1_000d;
            return sign + "$" + toFixed(v, abs >= 100_000d ? 0 : 1) + "k";
        }
        return sign + "$" + toFixed(abs, 0);
    }

    /** A signed delta string: {@code +$1.20} / {@code −$1.20} / {@code ±$0.00} for a near-zero move. */
    public static String signedMoney(double delta) {
        if (Math.abs(delta) < 0.005) {
            return "±" + fmtMoney(0);
        }
        return (delta > 0 ? "+" : MINUS) + fmtMoney(Math.abs(delta));
    }
}
