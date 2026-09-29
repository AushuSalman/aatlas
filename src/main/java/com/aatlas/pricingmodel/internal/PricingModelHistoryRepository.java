package com.aatlas.pricingmodel.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Newest first, keyset-paged on the id. UUID v7 sorts by creation time, so
 * "everything older than this id" is both the cursor and the index order.
 */
interface PricingModelHistoryRepository extends JpaRepository<PricingModelHistoryEntity, UUID> {

    List<PricingModelHistoryEntity> findByTenantIdOrderByIdDesc(UUID tenantId, Limit limit);

    List<PricingModelHistoryEntity> findByTenantIdAndIdLessThanOrderByIdDesc(UUID tenantId, UUID cursor, Limit limit);
}
