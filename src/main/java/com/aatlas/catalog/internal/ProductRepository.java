package com.aatlas.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** The item master, per tenant. */
interface ProductRepository extends JpaRepository<ProductEntity, UUID>, JpaSpecificationExecutor<ProductEntity> {

    long countByTenantId(UUID tenantId);

    Optional<ProductEntity> findByTenantIdAndItemNumber(UUID tenantId, String itemNumber);

    /** Case and padding are not meaningful in an ERP code, so HRD-1 and hrd-1 are one item. */
    boolean existsByTenantIdAndItemNumberIgnoreCase(UUID tenantId, String itemNumber);
}
