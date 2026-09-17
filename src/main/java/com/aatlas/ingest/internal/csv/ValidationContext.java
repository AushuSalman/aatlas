package com.aatlas.ingest.internal.csv;

import com.aatlas.ingest.internal.csv.ValueParsers.DateOrder;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;

/**
 * What a validator needs to know about the tenant a file is being loaded into.
 *
 * @param tenantCurrency the currency every row must be in (the tenant's trading currency; for
 *     the Hardin sample it is USD, and the loader converts at the reference rate)
 * @param dateOrder how an ambiguous {@code 03/04/2026} is read
 * @param today the clock's date, for "in the future" checks and defaults
 * @param knownItems lower-cased item numbers already in the catalogue (products files: decides
 *     whether a blank description is an error or a kept value)
 * @param knownBranches lower-cased store codes (competitor files: a branch is a valid region)
 * @param regionCodes lower-cased subdivision codes and the four region keys the tenant's country
 *     recognises
 * @param commodities the commodity keys the platform tracks
 */
public record ValidationContext(
        String tenantCurrency,
        DateOrder dateOrder,
        LocalDate today,
        Set<String> knownItems,
        Set<String> knownBranches,
        Set<String> regionCodes,
        Set<String> commodities) {

    public ValidationContext {
        tenantCurrency = tenantCurrency == null ? "USD" : tenantCurrency.toUpperCase(Locale.ROOT);
        dateOrder = dateOrder == null ? DateOrder.MDY : dateOrder;
        knownItems = knownItems == null ? Set.of() : Set.copyOf(knownItems);
        knownBranches = knownBranches == null ? Set.of() : Set.copyOf(knownBranches);
        regionCodes = regionCodes == null ? Set.of() : Set.copyOf(regionCodes);
        commodities = commodities == null ? DEFAULT_COMMODITIES : Set.copyOf(commodities);
    }

    /** The reference commodities when the tenant lookup is not available (unit tests). */
    static final Set<String> DEFAULT_COMMODITIES =
            Set.of("copper", "brass", "steel", "iron", "pvc", "pex", "equipment", "none");

    public static ValidationContext of(String tenantCurrency, DateOrder order, LocalDate today) {
        return new ValidationContext(tenantCurrency, order, today, null, null, null, null);
    }

    /** The message for a date that could not be read, worded for the tenant's order. */
    public String dateError(String raw) {
        return "Could not read the date \"" + raw + "\" — expected YYYY-MM-DD or "
                + (dateOrder == DateOrder.DMY ? "DD/MM/YYYY" : "MM/DD/YYYY");
    }

    public boolean knowsItem(String item) {
        return item != null && knownItems.contains(item.strip().toLowerCase(Locale.ROOT));
    }
}
