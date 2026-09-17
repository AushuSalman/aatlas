package com.aatlas.buy.internal;

import com.aatlas.approvals.ApprovalGranted;
import com.aatlas.approvals.ApprovalRejected;
import com.aatlas.common.tenant.TenantContext;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Finishes what {@link BuyService#select} could only provision when the seat could not
 * approve alone: on {@link ApprovalGranted} it writes the actual purchase; on {@link
 * ApprovalRejected} it marks the audit row rejected. A no-op for any decision this module did
 * not raise (matched by {@code buy_decisions.decision_id}, absent otherwise). See {@code
 * rfq.internal.ApprovalOutcomeListener} for the same pattern and why {@code TenantContext} has
 * to be bound explicitly on a Modulith worker thread.
 */
@Component
class BuyApprovalOutcomeListener {

    private final BuyService service;

    BuyApprovalOutcomeListener(BuyService service) {
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
