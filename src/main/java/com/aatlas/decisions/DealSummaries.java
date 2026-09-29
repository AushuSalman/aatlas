package com.aatlas.decisions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How often recommendations were followed, reduced from {@code deal}. What "adoption",
 * "follow rate" and the former "conversion" mean on every screen.
 */
public interface DealSummaries {

    /**
     * Deals on one side within the window, optionally at one branch ({@code destinationId}
     * is the store code the sale was priced at).
     */
    Adoption adoption(String side, LocalDate from, LocalDate to, String storeCodeOrNull);

    /** The most recent deal date on file, for readiness. */
    Optional<LocalDate> latestDeal();

    /**
     * Sell-side deals since a date with what was suggested and what was actually charged, for
     * the pricing model to learn a lean from. Newest first, at most {@code limit} rows. With
     * an item, that item's deals (at the branch when {@code storeCodeOrNull} is given, else
     * anywhere); with no item, every sell deal of the tenant.
     */
    List<Acceptance> acceptance(String itemNumberOrNull, String storeCodeOrNull, LocalDate since, int limit);

    /** Sell-side deals ever recorded for an item at a branch (tenant-wide with no branch): the trust ramp's n. */
    long priorApplied(String itemNumber, String storeCodeOrNull);

    /** Bulk sell strategies applied since a date, key → count ({@code max-profit}, {@code balanced}, {@code fast-movement}). */
    Map<String, Long> strategyPicks(LocalDate since);

    /**
     * One recorded deal's prices.
     *
     * @param storeCode the branch it was priced at ({@code destination_id}), nullable
     */
    record Acceptance(LocalDate date, String itemNumber, String storeCode, BigDecimal suggested, BigDecimal actual,
            boolean followed) {
    }

    record Adoption(int total, int followed) {

        /** 0-100, or empty with no deals - never a zero that reads as "nobody followed". */
        public Optional<Double> followRatePct() {
            return total == 0 ? Optional.empty() : Optional.of(Math.round(followed * 1000.0 / total) / 10.0);
        }
    }
}
