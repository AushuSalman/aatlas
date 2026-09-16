/**
 * What a user actually did: a recommendation applied, the deal it produced, the quote
 * breakdown behind a sell decision, and the history/impact views built from them.
 *
 * <p>Ported from the frontend's {@code intel/decisions.ts}, {@code intel/history.ts} and
 * the impact half of {@code platform/api.ts}. This is the real module every other wave-2
 * track (sell, buy, bulk) wrote a {@code DecisionRecorder}-shaped stand-in against - see
 * {@link com.aatlas.decisions.DecisionRecorder} for the shape they retarget onto at merge.
 *
 * <p>Application module. Types in this package root are the public API other modules may
 * depend on; everything under {@code internal} is implementation.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "decisions",
        allowedDependencies = {"analytics", "common", "ingest"})
package com.aatlas.decisions;
