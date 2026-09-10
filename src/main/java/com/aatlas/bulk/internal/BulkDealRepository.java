package com.aatlas.bulk.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BulkDealRepository extends JpaRepository<BulkDealEntity, UUID> {

    List<BulkDealEntity> findByTenantIdAndDecisionId(UUID tenantId, UUID decisionId);
}
