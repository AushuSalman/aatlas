package com.aatlas.history;

import java.math.BigDecimal;

/**
 * The market reference a recommendation starts from, and its provenance.
 *
 * <p>Sources, in the order the ladder tries them: {@code competitor} (median of observed
 * competitor prices), {@code peer} (median of what the tenant's other branches charge),
 * {@code benchmark} (reference gross margin over cost), {@code history} (the pair's own last
 * price).
 *
 * @param observations how many rows the figure rests on (competitors, peer stores, or 0)
 */
public record Anchor(BigDecimal value, String source, int observations) {

    public static final String COMPETITOR = "competitor";
    public static final String PEER = "peer";
    public static final String BENCHMARK = "benchmark";
    public static final String HISTORY = "history";
}
