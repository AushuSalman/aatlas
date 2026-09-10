package com.aatlas.suppliers.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupplierTermsRepository extends JpaRepository<SupplierTermsEntity, UUID> {

    Optional<SupplierTermsEntity> findBySupplierIdAndTenantId(UUID supplierId, UUID tenantId);

    List<SupplierTermsEntity> findByTenantId(UUID tenantId);
}
