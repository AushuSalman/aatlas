package com.aatlas.ingest.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Row issues for a batch, read by line and filtered by severity. */
interface ImportIssueRepository extends JpaRepository<ImportIssueEntity, UUID> {

    List<ImportIssueEntity> findByTenantIdAndBatchIdOrderByLineAsc(UUID tenantId, UUID batchId, Pageable pageable);

    List<ImportIssueEntity> findByTenantIdAndBatchIdAndSeverityOrderByLineAsc(
            UUID tenantId, UUID batchId, String severity, Pageable pageable);

    long countByTenantIdAndBatchId(UUID tenantId, UUID batchId);

    /**
     * Clears a batch's issues before revalidation.
     *
     * <p>A bulk delete rather than loading and removing: a mapping change on a large file
     * can invalidate thousands of rows, and none of them needs to become a managed entity
     * on the way to being forgotten.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ImportIssueEntity i where i.tenantId = :tenantId and i.batchId = :batchId")
    int deleteByBatch(@Param("tenantId") UUID tenantId, @Param("batchId") UUID batchId);
}
