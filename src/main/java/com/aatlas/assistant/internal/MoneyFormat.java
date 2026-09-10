package com.aatlas.assistant.internal;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * USD/en-US-only port of {@code src/lib/platform/money.ts}'s {@code fmtMoney}/
 * {@code fmtCompact}. The assistant is a text-answer API - unlike the numeric engines,
 * its {@code AssistantAnswer.lines[].value} fields are pre-formatted prose the frontend
 * renders verbatim, so this module needs the same string formatting the TypeScript uses.
 * Every fixture the golden files were generated from used the USD/en-US default (see
 * {@code golden/README.md}), so that is the only locale/currency this stand-in supports;
 * a tenant's own trading currency is a display concern the real {@code money.ts} still
 * owns on the frontend.
 */
final class MoneyFormat {

    private MoneyFormat() {
    }

    static String money(double usd) {
        return money(usd, 2);
    }

    static String money(double usd, int dp) {
        if (Double.isNaN(usd)) {
            return "—";
        }
        String sign = usd < 0 ? "−" : "";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(dp);
        nf.setMaximumFractionDigits(dp);
        return sign + "$" + nf.format(Math.abs(usd));
    }

    /** {@code $1.2k} / {@code $3.4M} style. */
    static String compact(double usd) {
        if (Double.isNaN(usd)) {
            return "—";
        }
        double abs = Math.abs(usd);
        String sign = usd < 0 ? "−" : "";
        if (abs >= 1_000_000_000d) {
            int dp = abs >= 10_000_000_000d ? 1 : 2;
            return sign + "$" + fixed(abs / 1_000_000_000d, dp) + "B";
        }
        if (abs >= 1_000_000d) {
            int dp = abs >= 10_000_000d ? 1 : 2;
            return sign + "$" + fixed(abs / 1_000_000d, dp) + "M";
        }
        if (abs >= 1_000d) {
            int dp = abs >= 100_000d ? 0 : 1;
            return sign + "$" + fixed(abs / 1_000d, dp) + "k";
        }
        return sign + "$" + fixed(abs, 0);
    }

    private static String fixed(double v, int dp) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(dp);
        nf.setMaximumFractionDigits(dp);
        nf.setGroupingUsed(false);
        return nf.format(v);
    }
}
