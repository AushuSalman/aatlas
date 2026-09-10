package com.aatlas.suppliers.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupplierReviewRepository extends JpaRepository<SupplierReviewEntity, UUID> {

    List<SupplierReviewEntity> findByTenantIdAndSupplierIdOrderByPositionAsc(UUID tenantId, UUID supplierId);

    List<SupplierReviewEntity> findByTenantIdOrderBySupplierIdAscPositionAsc(UUID tenantId);
}
