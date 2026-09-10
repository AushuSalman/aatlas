package com.aatlas.catalog.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Branches. Every query carries {@code tenant_id} explicitly; row-level security is the
 * second line, not the first.
 */
interface StoreRepository extends JpaRepository<StoreEntity, UUID>, JpaSpecificationExecutor<StoreEntity> {

    /** The gate every catalogue read passes: a tenant with no branches has no catalogue. */
    boolean existsByTenantId(UUID tenantId);

    long countByTenantId(UUID tenantId);

    Optional<StoreEntity> findByTenantIdAndStoreCode(UUID tenantId, String storeCode);

    Optional<StoreEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    List<StoreEntity> findByTenantIdOrderByStoreCode(UUID tenantId);

    /** The country this tenant's branches are in, which is what picks its market regions. */
    @Query("select distinct s.country from StoreEntity s where s.tenantId = :tenantId")
    List<String> findCountries(@Param("tenantId") UUID tenantId);

    /** Branches with sales history for one item, in code order. */
    @Query("""
            select s from StoreEntity s, ProductStoreEntity ps
            where ps.tenantId = :tenantId and ps.productId = :productId and ps.sells = true
              and s.tenantId = :tenantId and s.id = ps.storeId
            order by s.storeCode
            """)
    List<StoreEntity> findSellingProduct(@Param("tenantId") UUID tenantId, @Param("productId") UUID productId);
}
