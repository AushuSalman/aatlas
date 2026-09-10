package com.aatlas.suppliers.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupplierRatingRepository extends JpaRepository<SupplierRatingEntity, UUID> {

    Optional<SupplierRatingEntity> findBySupplierIdAndTenantId(UUID supplierId, UUID tenantId);

    List<SupplierRatingEntity> findByTenantIdOrderByRatingDesc(UUID tenantId);
}
