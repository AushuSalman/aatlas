package com.aatlas.ingest.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Import batches, always reached through the tenant that owns them. */
interface ImportBatchRepository extends JpaRepository<ImportBatchEntity, UUID> {

    Optional<ImportBatchEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<ImportBatchEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    List<ImportBatchEntity> findByTenantIdAndKindOrderByCreatedAtDesc(UUID tenantId, String kind);

    List<ImportBatchEntity> findByTenantIdAndSourceOrderByCreatedAtAsc(UUID tenantId, String source);

    List<ImportBatchEntity> findByTenantIdAndKindAndSource(UUID tenantId, String kind, String source);

    boolean existsByTenantIdAndSource(UUID tenantId, String source);

    boolean existsByTenantIdAndSourceAndStatus(UUID tenantId, String source, ImportBatchStatus status);

    boolean existsByTenantIdAndKindAndSourceAndStatusIn(
            UUID tenantId, String kind, String source, Collection<ImportBatchStatus> statuses);

    /** An earlier committed batch of the same kind with the same bytes: the re-upload check. */
    Optional<ImportBatchEntity> findFirstByTenantIdAndKindAndContentHashAndStatusAndIdNotOrderByCommittedAtDesc(
            UUID tenantId, String kind, String contentHash, ImportBatchStatus status, UUID id);
}
