package com.aatlas.ingest.internal.csv;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * One row that passed validation, read into its types. One record per {@link ImportKind}.
 *
 * <p>{@link #forSample} exists for the Hardin sample: its dates are moved forward so the
 * history ends today, and its dollars are converted for a tenant that does not trade in them.
 * Uploads are never shifted or converted.
 */
public interface AcceptedRow {

    /** 1-based line in the file, counting the header - the number a spreadsheet shows. */
    int line();

    /** The row keyed by field name, for the preview table. */
    Map<String, Object> preview();

    /** This row with every date moved {@code days} forward and every money cell multiplied by {@code fx}. */
    AcceptedRow forSample(int days, BigDecimal fx);

    static LocalDate shift(LocalDate date, int days) {
        return date == null ? null : date.plusDays(days);
    }

    static BigDecimal convert(BigDecimal money, BigDecimal fx) {
        if (money == null || fx == null || fx.compareTo(BigDecimal.ONE) == 0) {
            return money;
        }
        return money.multiply(fx).setScale(4, RoundingMode.HALF_UP);
    }
}
