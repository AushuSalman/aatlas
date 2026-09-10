package com.aatlas.bulk.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BulkDecisionRepository extends JpaRepository<BulkDecisionEntity, UUID> {

    List<BulkDecisionEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
