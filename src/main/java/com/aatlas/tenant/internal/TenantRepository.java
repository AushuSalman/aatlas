package com.aatlas.tenant.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenants by id and by slug. Not tenant-scoped: this table defines the tenants. */
interface TenantRepository extends JpaRepository<TenantEntity, UUID> {

    boolean existsBySlug(String slug);
}
