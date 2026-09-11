package com.aatlas.ingest.internal;

import com.aatlas.common.tenant.TenantContext;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a commit off the request thread.
 *
 * <p>Two things have to be true for this to be correct, and neither is automatic.
 *
 * <p><b>It must start after the caller's transaction commits.</b> The worker reads the batch
 * row the caller just set to {@code COMMITTING}; dispatched immediately it would sometimes
 * read the row as it was before, decide the import had not started, and load everything
 * twice. Registering a synchronization makes the ordering a guarantee rather than a
 * question of which thread wins.
 *
 * <p><b>It must carry the tenant.</b> {@link TenantContext} is inheritable, but a task handed
 * to an executor is not inheriting anything - it runs on a pooled virtual thread with no
 * relationship to the request. The actor is passed explicitly and rebound on the far side,
 * which is also what puts {@code app.tenant_id} on the worker's connection so row-level
 * security lets it write at all.
 */
@Component
class ImportCommitRunner {

    private static final Logger log = LoggerFactory.getLogger(ImportCommitRunner.class);

    /** Self, through the proxy - a direct call would skip the {@code @Async} advice. */
    private final ObjectProvider<ImportCommitRunner> self;
    private final ImportCommitter committer;

    ImportCommitRunner(ObjectProvider<ImportCommitRunner> self, ImportCommitter committer) {
        this.self = self;
        this.committer = committer;
    }

    /** Schedules {@link #execute} for after the current transaction commits. */
    void runAfterCommit(TenantContext.Actor actor, UUID batchId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to wait for: a test, or a caller that manages its own.
            self.getObject().execute(actor, batchId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.getObject().execute(actor, batchId);
            }
        });
    }

    /**
     * Loads the batch, and records the failure if it cannot.
     *
     * <p>Nothing rethrows past here. An uncaught exception on an async virtual thread reaches
     * an exception handler nobody is reading, and the user would be left watching a status
     * that never changes. Every ending writes a terminal state.
     */
    @Async
    public void execute(TenantContext.Actor actor, UUID batchId) {
        TenantContext.runAs(actor, () -> {
            try {
                committer.load(actor.tenantId(), batchId);
            } catch (RuntimeException ex) {
                log.error("Import {} failed to load", batchId, ex);
                try {
                    committer.markFailed(actor.tenantId(), batchId, describe(ex));
                } catch (RuntimeException nested) {
                    // The database is where the failure would have been recorded, so if that
                    // is also gone the log is the only place left to say so.
                    log.error("Import {} failed and its failure could not be recorded", batchId, nested);
                }
            }
        });
    }

    /**
     * A sentence for the user.
     *
     * <p>Deliberately not the exception's own message: those carry SQL, column names and
     * stack context, which tell the user nothing and tell an attacker something. The log has
     * the detail, keyed by the batch id.
     */
    private static String describe(RuntimeException ex) {
        return ex instanceof com.aatlas.common.error.ApiException api
                ? api.getMessage()
                : "The import could not be completed. Nothing was loaded, so it is safe to try again.";
    }
}
