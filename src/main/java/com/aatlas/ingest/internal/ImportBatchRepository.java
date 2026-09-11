package com.aatlas.ingest.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Import batches, always reached through the tenant that owns them. */
interface ImportBatchRepository extends JpaRepository<ImportBatchEntity, UUID> {

    Optional<ImportBatchEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<ImportBatchEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
