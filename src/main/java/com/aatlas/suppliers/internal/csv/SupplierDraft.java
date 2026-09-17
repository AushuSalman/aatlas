package com.aatlas.suppliers.internal.csv;

import java.util.List;

/**
 * One supplier read out of a file, typed in by hand, or merged from an existing record during
 * a patch - normalised and ready to become a panel row.
 *
 * <p>The manual-entry and CSV-import routes always fill in every optional with an assumed
 * value (see {@code SupplierImportValidator}, {@code AddSupplierRequest.toDraft}), reporting
 * what was assumed as a warning rather than leaving it absent. A patch on a supplier the
 * platform only knows from a purchase order is different: some figures may genuinely never
 * have been provided, and {@code null} there is "not provided", not an assumption to derive a
 * star from. The numeric fields are boxed for exactly that second case; {@link SupplierWriter}
 * treats a null the same way whichever route produced it.
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
        Integer yearsTrading,
        List<String> certifications,
        Integer leadTimeDays,
        Double otifPct,
        Double priceIndex,
        Double defectPct,
        Boolean holdsStock,
        Double communication,
        String contactName,
        String contactEmail,
        String contactPhone,
        int line) {

    /** What an absent optional column becomes on manual entry or CSV import. Mirrors {@code BLANK_DRAFT} in the frontend. */
    public static final double DEFAULT_PRICE_INDEX = 100;
    public static final double DEFAULT_DEFECT_PCT = 1.5;
    public static final double DEFAULT_COMMUNICATION = 3.5;
    public static final int DEFAULT_YEARS_TRADING = 10;
    public static final String DEFAULT_CATEGORY = "Fittings";
}
