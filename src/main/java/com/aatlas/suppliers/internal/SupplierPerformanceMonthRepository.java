package com.aatlas.suppliers.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupplierPerformanceMonthRepository extends JpaRepository<SupplierPerformanceMonthEntity, UUID> {

    List<SupplierPerformanceMonthEntity> findByTenantIdAndSupplierIdOrderByMonthAsc(UUID tenantId, UUID supplierId);
}
