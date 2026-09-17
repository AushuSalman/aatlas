package com.aatlas.ingest.internal;

import com.aatlas.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs the sample loads off the request thread, for the reload endpoint.
 *
 * <p>A separate bean so {@code @Async} sits on its own proxy (see {@link ImportCommitRunner}
 * for why). The actor is rebound explicitly on the far side: a virtual thread from the pool
 * inherits nothing from the request.
 */
@Component
class SampleHistoryRunner {

    private final SampleHistoryLoader loader;

    SampleHistoryRunner(SampleHistoryLoader loader) {
        this.loader = loader;
    }

    @Async
    public void load(TenantContext.Actor actor, List<UUID> batchIds) {
        TenantContext.runAs(actor, () -> loader.loadClaimed(actor.tenantId(), batchIds));
    }
}
