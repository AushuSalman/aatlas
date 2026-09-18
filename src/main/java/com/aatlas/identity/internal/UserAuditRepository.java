package com.aatlas.identity.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** The Users activity log. Backed by {@code user_audit_tenant_idx}. */
interface UserAuditRepository extends JpaRepository<UserAuditEntity, UUID> {

    List<UserAuditEntity> findTop200ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
