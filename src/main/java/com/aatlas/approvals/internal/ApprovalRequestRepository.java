package com.aatlas.approvals.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ApprovalRequestRepository extends JpaRepository<ApprovalRequestEntity, UUID> {

    Optional<ApprovalRequestEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<ApprovalRequestEntity> findByTenantIdAndApproverRoleAndStatusOrderByCreatedAtDesc(
            UUID tenantId, String approverRole, ApprovalRequestEntity.Status status);

    List<ApprovalRequestEntity> findByTenantIdAndRequestedByOrderByCreatedAtDesc(UUID tenantId, UUID requestedBy);
}
