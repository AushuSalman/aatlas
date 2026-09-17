package com.aatlas.history;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Prices the tenant observed at competitors ({@code competitor_prices}). Never fetched by the
 * platform: only what a file or a person supplied, which is what makes them citable.
 */
public interface CompetitorPrices {

    /** Observations older than this are not an anchor. */
    int MAX_AGE_DAYS = 180;

    /** The latest observation per competitor, region- or store-matched rows first. */
    List<Observation> forItem(UUID productId, String regionKeyOrNull, UUID storeIdOrNull, LocalDate today);

    /**
     * The median over matched observations when any match the region or store, else over all;
     * empty when there are none within {@link #MAX_AGE_DAYS}.
     */
    Optional<Anchor> anchor(UUID productId, String regionKeyOrNull, UUID storeIdOrNull, LocalDate today);

    /** Median per product over every recent observation, in one query. */
    Map<UUID, Anchor> medians(LocalDate today);

    CompetitorCoverage coverage(LocalDate today);

    record Observation(String competitor, BigDecimal price, LocalDate observedAt, String regionKey, UUID storeId,
            String sourceUrl, boolean matched) {
    }

    record CompetitorCoverage(int items, int observations, int competitors) {
    }
}
