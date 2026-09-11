package com.aatlas.ingest.internal;

/**
 * Where an upload has got to.
 *
 * <p>Doubles as the job record for the commit. The blueprint calls for imports to return a
 * job id rather than block, and rather than stand up a jobs table for one caller, the batch
 * row is the job: {@code COMMITTING} is "running", and the client polls
 * {@code GET /imports/{id}} it already knows the id of.
 */
public enum ImportBatchStatus {

    /** Parsed and checked. The only state a commit may start from. */
    VALIDATED,

    /** A required field has no column. The user has to choose one before anything can load. */
    NEEDS_MAPPING,

    /** The loader is running. */
    COMMITTING,

    /** Rows are in {@code sales_transactions}. Terminal. */
    COMMITTED,

    /** The loader stopped and wrote nothing; {@code failureReason} says why. Terminal. */
    FAILED
}
