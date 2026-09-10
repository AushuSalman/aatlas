package com.aatlas.suppliers.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The panel in numbers. Mirrors {@code PanelSummary} in the frontend's {@code intel/suppliers.ts}.
 *
 * @param spendRated4Pct share of twelve-month spend with suppliers rated four stars or better
 */
@Schema(name = "PanelSummary")
record PanelSummary(int size, int added, double avgRating, int spendRated4Pct, int weak, double avgOtifPct) {

    /** The endpoint adds a count of distinct quoting currencies on the panel. */
    record View(int size, int added, double avgRating, int spendRated4Pct, int weak, double avgOtifPct,
            int currencies) {

        static View of(PanelSummary s, int currencies) {
            return new View(s.size(), s.added(), s.avgRating(), s.spendRated4Pct(), s.weak(), s.avgOtifPct(),
                    currencies);
        }
    }
}
