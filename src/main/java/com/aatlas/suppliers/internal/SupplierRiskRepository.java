package com.aatlas.suppliers.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupplierRiskRepository extends JpaRepository<SupplierRiskEntity, UUID> {

    Optional<SupplierRiskEntity> findBySupplierIdAndTenantId(UUID supplierId, UUID tenantId);

    List<SupplierRiskEntity> findByTenantId(UUID tenantId);
}
