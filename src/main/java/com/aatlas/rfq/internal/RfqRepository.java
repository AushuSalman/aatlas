package com.aatlas.rfq.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface RfqRepository extends JpaRepository<RfqEntity, UUID>, JpaSpecificationExecutor<RfqEntity> {

    Optional<RfqEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<RfqEntity> findByTenantIdAndDecisionId(UUID tenantId, UUID decisionId);

    List<RfqEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, org.springframework.data.domain.Pageable pageable);

    List<RfqEntity> findByTenantIdAndStatusOrderByCreatedAtDesc(
            UUID tenantId, RfqEntity.Status status, org.springframework.data.domain.Pageable pageable);
}
