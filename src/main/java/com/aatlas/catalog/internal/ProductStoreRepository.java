package com.aatlas.catalog.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Item-at-branch history rows. Read through {@link StoreRepository#findSellingProduct}. */
interface ProductStoreRepository extends JpaRepository<ProductStoreEntity, UUID> {

    long countByTenantId(UUID tenantId);
}
