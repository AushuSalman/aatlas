package com.aatlas.approvals.internal;

import com.aatlas.approvals.ApprovalGranted;
import com.aatlas.approvals.ApprovalLimits;
import com.aatlas.approvals.ApprovalRejected;
import com.aatlas.approvals.ApprovalRequest;
import com.aatlas.approvals.ApprovalRequester;
import com.aatlas.approvals.RaiseApprovalRequest;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.event.DomainEventPublisher;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.Decision;
import com.aatlas.decisions.DecisionRecorder;
import com.aatlas.decisions.DecisionStatus;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises, lists and decides approval requests. Never writes a deal itself - approving or
 * rejecting only moves {@link Decision#status} (via {@link DecisionRecorder#resolve}) and
 * publishes {@link ApprovalGranted}/{@link ApprovalRejected}; the module that raised the
 * request is the one that finishes committing it.
 *
 * <p>{@link #approve}/{@link #reject} do not yet check that the caller actually holds
 * {@code approverRole} - only that they are a signed-in user of this tenant. There is no
 * seat-invite flow yet (a tenant has exactly one user, the one who signed up), so a real
 * check would make every request permanently unactionable rather than enforcing anything;
 * add it once a tenant can hold more than one seat.
 */
@Service
class ApprovalService implements ApprovalRequester {

    private final ApprovalRequestRepository repository;
    private final DecisionRecorder decisions;
    private final PolicyReader policy;
    private final AatlasClock clock;
    private final DomainEventPublisher events;

    ApprovalService(ApprovalRequestRepository repository, DecisionRecorder decisions, PolicyReader policy,
            AatlasClock clock, DomainEventPublisher events) {
        this.repository = repository;
        this.decisions = decisions;
        this.policy = policy;
        this.clock = clock;
        this.events = events;
    }

    private UUID currentUserId() {
        return TenantContext.currentUserId()
                .orElseThrow(() -> ApiException.forbidden("Approvals require a signed-in user."));
    }

    private String currentRole() {
        return TenantContext.current().map(TenantContext.Actor::role)
                .orElseThrow(() -> ApiException.forbidden("Approvals require a signed-in user."));
    }

    @Override
    @Transactional
    public ApprovalRequest raise(RaiseApprovalRequest request) {
        Decision decision = decisions.get(request.decisionId());
        if (decision.status() != DecisionStatus.PENDING) {
            throw ApiException.conflict("decision_not_pending",
                    "Decision " + decision.id() + " is " + decision.status().wire() + ", not pending approval.");
        }
        ApprovalRequestEntity entity = new ApprovalRequestEntity(
                request.decisionId(), currentUserId(), request.approverRole(), request.amount(), request.note());
        entity = repository.save(entity);
        return Mappers.toApprovalRequest(entity, decision, currentUserId());
    }

    @Transactional(readOnly = true)
    ApprovalRequest get(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        ApprovalRequestEntity entity = repository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("ApprovalRequest", id));
        return Mappers.toApprovalRequest(entity, decisions.get(entity.getDecisionId()), currentUserId());
    }

    /** Pending requests waiting on my role's sign-off, plus every request I raised myself, newest first. */
    @Transactional(readOnly = true)
    List<ApprovalRequest> list() {
        UUID tenantId = TenantContext.requireTenantId();
        UUID userId = currentUserId();

        Map<UUID, ApprovalRequestEntity> byId = new LinkedHashMap<>();
        for (ApprovalRequestEntity e : repository.findByTenantIdAndApproverRoleAndStatusOrderByCreatedAtDesc(
                tenantId, currentRole(), ApprovalRequestEntity.Status.pending)) {
            byId.put(e.getId(), e);
        }
        for (ApprovalRequestEntity e : repository.findByTenantIdAndRequestedByOrderByCreatedAtDesc(tenantId, userId)) {
            byId.put(e.getId(), e);
        }

        List<ApprovalRequestEntity> merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparing(ApprovalRequestEntity::getCreatedAt).reversed());
        return merged.stream()
                .map(e -> Mappers.toApprovalRequest(e, decisions.get(e.getDecisionId()), userId))
                .toList();
    }

    @Transactional
    ApprovalRequest approve(UUID id, String note) {
        return decide(id, ApprovalRequestEntity.Status.approved, note);
    }

    @Transactional
    ApprovalRequest reject(UUID id, String note) {
        return decide(id, ApprovalRequestEntity.Status.rejected, note);
    }

    private ApprovalRequest decide(UUID id, ApprovalRequestEntity.Status outcome, String note) {
        UUID tenantId = TenantContext.requireTenantId();
        ApprovalRequestEntity entity = repository.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("ApprovalRequest", id));
        if (entity.getStatus() != ApprovalRequestEntity.Status.pending) {
            throw ApiException.conflict("already_decided",
                    "Approval request " + id + " is already " + entity.getStatus() + ".");
        }

        UUID userId = currentUserId();
        entity.decide(outcome, userId, clock.now(), note);
        entity = repository.save(entity);

        DecisionStatus decisionStatus = outcome == ApprovalRequestEntity.Status.approved
                ? DecisionStatus.APPROVED
                : DecisionStatus.REJECTED;
        Decision decision = decisions.resolve(entity.getDecisionId(), decisionStatus);

        events.publish(outcome == ApprovalRequestEntity.Status.approved
                ? new ApprovalGranted(entity.getId(), entity.getDecisionId(), tenantId, clock.now())
                : new ApprovalRejected(entity.getId(), entity.getDecisionId(), tenantId, note, clock.now()));

        return Mappers.toApprovalRequest(entity, decision, userId);
    }

    @Transactional(readOnly = true)
    ApprovalLimits limits() {
        Persona persona = policy.personaFor(TenantContext.requireTenantId(), currentRole());
        return new ApprovalLimits(persona.approveLimit(), persona.approver());
    }
}
