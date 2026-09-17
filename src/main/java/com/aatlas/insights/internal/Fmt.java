package com.aatlas.insights.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * The handful of {@code platform/money.ts} and JavaScript {@code toFixed} formatting rules
 * that leak into prose fields of the engines below (a KPI footer, an opportunity detail
 * line). The server always renders in USD / en-US - the frontend's per-tenant currency
 * conversion is a display concern it already owns, per the wave-2 brief - so this is a
 * fixed-locale subset of {@code money.ts}, not a port of its currency table.
 *
 * <p>Two JavaScript conventions the golden fixtures pin: a negative sign in prose is
 * U+2212 MINUS SIGN, not the ASCII hyphen, and {@code Math.round} rounds half toward positive
 * infinity, which plain {@link Math#round(double)} already matches.
 */
final class Fmt {

    private static final char MINUS = '−';

    private Fmt() {
    }

    static double round(double n, int places) {
        double f = Math.pow(10, places);
        return Math.round(n * f) / f;
    }

    /** A nullable {@link BigDecimal} as a nullable wire double - {@code null} stays {@code null}, never 0. */
    static Double dv(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    /** {@code dv}, but 0 when absent - for internal aggregation where "no input" and "adds nothing" are the same. */
    static double dv0(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    static double round2(double n) {
        return round(n, 2);
    }

    static double round1(double n) {
        return round(n, 1);
    }

    /** {@code n.toFixed(places)} - always shows the sign only if negative (ASCII hyphen, JS's own). */
    static String toFixed(double n, int places) {
        BigDecimal bd = BigDecimal.valueOf(n).setScale(places, RoundingMode.HALF_UP);
        return bd.toPlainString();
    }

    /** {@code fmtMoney(usd)}: {@code $1,234.56}, USD, en-US, two decimals unless overridden. */
    static String money(double usd) {
        return money(usd, 2);
    }

    static String money(double usd, int decimals) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        String pattern = decimals > 0 ? "#,##0." + "0".repeat(decimals) : "#,##0";
        DecimalFormat format = new DecimalFormat(pattern, symbols);
        double abs = Math.abs(usd);
        String sign = usd < 0 ? String.valueOf(MINUS) : "";
        return sign + "$" + format.format(abs);
    }

    /** {@code fmtCompact(usd)}: {@code $1.2k} / {@code $3.4M} / {@code $1.20B} style. */
    static String compact(double usd) {
        double abs = Math.abs(usd);
        String sign = usd < 0 ? String.valueOf(MINUS) : "";
        if (abs >= 1_000_000_000d) {
            return sign + "$" + fixed(abs / 1_000_000_000d, abs >= 10_000_000_000d ? 1 : 2) + "B";
        }
        if (abs >= 1_000_000d) {
            return sign + "$" + fixed(abs / 1_000_000d, abs >= 10_000_000d ? 1 : 2) + "M";
        }
        if (abs >= 1_000d) {
            return sign + "$" + fixed(abs / 1_000d, abs >= 100_000d ? 0 : 1) + "k";
        }
        return sign + "$" + fixed(abs, 0);
    }

    private static String fixed(double n, int places) {
        return BigDecimal.valueOf(n).setScale(places, RoundingMode.HALF_UP).toPlainString();
    }

    /** Integer counts, grouped: {@code toLocaleString('en-US')}. */
    static String count(long n) {
        return new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US)).format(n);
    }
}
