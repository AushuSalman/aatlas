package com.aatlas.decisions.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DecisionRepository extends JpaRepository<DecisionEntity, UUID>, JpaSpecificationExecutor<DecisionEntity> {

    Optional<DecisionEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Newest-first, per tenant, capped at {@code max} rows (the History screen's "recent
     * decisions" reads a bounded window rather than every decision a tenant has ever made -
     * see {@code HistoryService.getHistory}).
     */
    List<DecisionEntity> findByTenantIdOrderByCreatedAtDesc(UUID tenantId, org.springframework.data.domain.Pageable pageable);

    @Modifying
    @Query("delete from DecisionEntity d where d.tenantId = :tenantId and d.userId = :userId")
    int deleteByTenantIdAndUserId(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
