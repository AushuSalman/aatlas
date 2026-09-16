package com.aatlas.rfq.internal;

import com.aatlas.approvals.ApprovalGranted;
import com.aatlas.approvals.ApprovalRejected;
import com.aatlas.common.tenant.TenantContext;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Finishes what {@code RfqService#award} could only provision when the seat could not
 * approve alone: on {@link ApprovalGranted} it writes the actual purchase; on {@link
 * ApprovalRejected} it reopens the round for a different award. A no-op for any decision
 * this module did not raise (matched by {@code rfq.decision_id}, absent otherwise).
 *
 * <p>{@code @ApplicationModuleListener} runs on Modulith's own worker thread, which does not
 * inherit {@code TenantContext} the way a request thread does (see {@code TenantContext}'s
 * own javadoc and {@code suppliers.internal.SuppliersSeedListener}'s note on the same
 * thing) - so the tenant carried on the event is bound explicitly with {@link
 * TenantContext#runAs} before touching anything tenant-scoped.
 */
@Component
class ApprovalOutcomeListener {

    private final RfqService service;

    ApprovalOutcomeListener(RfqService service) {
        this.service = service;
    }

    @ApplicationModuleListener
    void on(ApprovalGranted event) {
        TenantContext.runAs(TenantContext.Actor.system(event.tenantId()),
                () -> service.onApprovalGranted(event.decisionId()));
    }

    @ApplicationModuleListener
    void on(ApprovalRejected event) {
        TenantContext.runAs(TenantContext.Actor.system(event.tenantId()),
                () -> service.onApprovalRejected(event.decisionId()));
    }
}
