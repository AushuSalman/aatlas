package com.aatlas.suppliers.internal.csv;

import java.util.List;

/**
 * One supplier read out of a file, normalised and ready to become a panel row.
 *
 * <p>Every field is already clamped to its allowed range and every absent optional has its
 * assumed value filled in, so nothing downstream has to ask "was this supplied?". What was
 * assumed rather than read is reported as a warning against the row instead, which is the
 * only way a buyer can tell a real 100 price index from a missing one.
 *
 * @param key {@code name|country}, normalised. Two rows sharing it are one supplier.
 * @param priceIndex 100 is market; 94 is six per cent under
 * @param communication 1-5 stars, the one input no system measures
 * @param line the file line this came from, so a draft can be traced back
 */
public record SupplierDraft(
        String key,
        String name,
        String country,
        String city,
        String website,
        String category,
        int yearsTrading,
        List<String> certifications,
        int leadTimeDays,
        double otifPct,
        double priceIndex,
        double defectPct,
        boolean holdsStock,
        double communication,
        String contactName,
        String contactEmail,
        String contactPhone,
        int line) {

    /** What an absent optional column becomes. Mirrors {@code BLANK_DRAFT} in the frontend. */
    public static final double DEFAULT_PRICE_INDEX = 100;
    public static final double DEFAULT_DEFECT_PCT = 1.5;
    public static final double DEFAULT_COMMUNICATION = 3.5;
    public static final int DEFAULT_YEARS_TRADING = 10;
    public static final String DEFAULT_CATEGORY = "Fittings";
}
