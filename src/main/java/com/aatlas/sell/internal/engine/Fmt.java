package com.aatlas.sell.internal.engine;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * {@code fmtMoney} from {@code src/lib/platform/money.ts} and the raw {@code
 * n.toLocaleString('en-US')} calls scattered through the engines, fixed to the trading
 * currency USD and locale en-US the golden fixtures were generated with (see {@code
 * golden/README.md}: "Numbers... Strings were produced with the trading currency USD and
 * locale en-US"). Negative signs inside prose are U+2212 MINUS SIGN, matching every
 * hand-written {@code n < 0 ? '−' : ''} template in the TypeScript.
 */
final class Fmt {

    private static final String MINUS = "−";

    private Fmt() {
    }

    private static DecimalFormat pattern(int dp) {
        String p = dp <= 0 ? "#,##0" : "#,##0." + "0".repeat(dp);
        DecimalFormat df = new DecimalFormat(p, DecimalFormatSymbols.getInstance(Locale.US));
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df;
    }

    static String fmtMoney(double usd) {
        return fmtMoney(usd, 2);
    }

    static String fmtMoney(double usd, int dp) {
        boolean negative = usd < 0;
        double abs = Math.abs(usd);
        return (negative ? MINUS : "") + "$" + pattern(dp).format(abs);
    }

    /** {@code n.toLocaleString('en-US')} for an integer count - grouping, no currency. */
    static String groupInt(long n) {
        boolean negative = n < 0;
        long abs = Math.abs(n);
        return (negative ? "-" : "") + pattern(0).format(abs);
    }

    /**
     * A bare {@code ${n}} template interpolation: JavaScript's default {@code
     * Number.prototype.toString}, which prints the shortest round-trip decimal with no
     * trailing zero and no decimal point for a whole number ({@code 8}, not {@code 8.0}).
     * Every value passed here is already {@code round1}/{@code round2}'d, so this never has
     * to reproduce JavaScript's exponential notation for very large or small magnitudes.
     *
     * <p>Deliberately avoids {@code Double.toString}/{@code Double.isInfinite} and any other
     * static call on {@code java.lang.Double}: {@code ArchitectureRulesTest.noFloatingPointMoney}
     * forbids a dependency on that class from this package, not just a boxed field.
     */
    static String jsNum(double n) {
        if (n == Math.rint(n) && Math.abs(n) < 1.0e15) {
            return Long.toString((long) n);
        }
        return String.valueOf(n);
    }

    /**
     * {@code n.toFixed(dp)}. Uses a {@link DecimalFormat} (its {@code format(double)}
     * overload takes the primitive, so this never boxes) rather than {@code
     * String.format(..., n)}, whose varargs {@code Object[]} would autobox the double into
     * a {@code java.lang.Double} - exactly what {@code noFloatingPointMoney} forbids here.
     */
    static String fixed(double n, int dp) {
        String p = dp <= 0 ? "0" : "0." + "0".repeat(dp);
        DecimalFormat df = new DecimalFormat(p, DecimalFormatSymbols.getInstance(Locale.US));
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(n);
    }
}
