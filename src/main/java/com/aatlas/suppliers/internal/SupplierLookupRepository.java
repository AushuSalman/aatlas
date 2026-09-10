package com.aatlas.suppliers.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SupplierLookupRepository extends JpaRepository<SupplierLookupEntity, UUID> {

    Optional<SupplierLookupEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
