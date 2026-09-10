package com.aatlas.suppliers.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupplierRepository extends JpaRepository<SupplierEntity, UUID> {

    List<SupplierEntity> findByTenantIdOrderByIdAsc(UUID tenantId);

    Optional<SupplierEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<SupplierEntity> findByTenantIdAndSupplierKey(UUID tenantId, String supplierKey);

    boolean existsByTenantIdAndSupplierKey(UUID tenantId, String supplierKey);

    long countByTenantId(UUID tenantId);
}
