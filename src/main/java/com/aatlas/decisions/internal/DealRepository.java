package com.aatlas.decisions.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface DealRepository extends JpaRepository<DealEntity, UUID>, JpaSpecificationExecutor<DealEntity> {

    boolean existsByTenantId(UUID tenantId);

    /** Every deal for a tenant, newest first - what {@code buildImpact}/{@code getHistory} reduce. */
    List<DealEntity> findByTenantIdOrderByDealDateDescCreatedAtDesc(UUID tenantId);

    List<DealEntity> findByTenantIdAndSideOrderByDealDateDesc(UUID tenantId, String side);

    List<DealEntity> findByTenantIdAndDecisionId(UUID tenantId, UUID decisionId);

    long countByTenantIdAndDealDateBetween(UUID tenantId, LocalDate from, LocalDate to);
}
