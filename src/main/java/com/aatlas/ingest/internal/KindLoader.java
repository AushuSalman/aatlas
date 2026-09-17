package com.aatlas.ingest.internal;

import com.aatlas.ingest.internal.csv.AcceptedRow;
import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.ValidationContext;
import java.util.UUID;

/**
 * Writes the accepted rows of one import kind into the fact tables.
 *
 * <p>JDBC rather than JPA, and that is the whole design. A 24-month export is hundreds of
 * thousands of rows; persisting each through an entity manager means a managed object, a
 * dirty check and a flush per row, and a first-level cache that grows until the heap gives
 * out. Batched prepared statements write the same rows in a fraction of the time and a
 * constant amount of memory. None of the fact tables is mapped as an entity, so nobody can
 * undo this by accident.
 *
 * <p>A {@link Load} is one import in progress: it holds the resolution caches and the
 * pending batch, is called inside the committer's transaction, and is not thread-safe.
 */
interface KindLoader<R extends AcceptedRow> {

    ImportKind kind();

    /**
     * @param source {@code upload} or {@code sample}; decides the {@code source} tag on every
     *     row and on every product, branch, customer or supplier the load creates
     * @param ctx the tenant's currency (what every row is written in) and today's date
     */
    Load<R> begin(UUID tenantId, UUID batchId, String source, ValidationContext ctx);

    interface Load<R extends AcceptedRow> {

        void add(R row);

        LoadResult finish();
    }
}
