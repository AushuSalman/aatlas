package com.aatlas.decisions;

import java.time.LocalDate;
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

    record Adoption(int total, int followed) {

        /** 0-100, or empty with no deals - never a zero that reads as "nobody followed". */
        public Optional<Double> followRatePct() {
            return total == 0 ? Optional.empty() : Optional.of(Math.round(followed * 1000.0 / total) / 10.0);
        }
    }
}
