package com.aatlas.history;

import java.util.UUID;

/**
 * Drops everything cached for a tenant after its rows change.
 *
 * <p>Called by the writers - an import commit or rollback, a price write, a branch placed
 * in a region, a supplier edit, an award - rather than driven by an event, so the request
 * that follows the write already sees fresh figures.
 */
public interface HistoryCaches {

    /** Evicts now. */
    void evict(UUID tenantId);

    /**
     * Evicts once the current transaction commits, so a reader on another thread cannot
     * refill the cache from rows the writer has not committed yet. Evicts now when no
     * transaction is active.
     */
    void evictAfterCommit(UUID tenantId);
}
