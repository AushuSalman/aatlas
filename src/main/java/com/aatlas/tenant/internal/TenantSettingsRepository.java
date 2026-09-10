package com.aatlas.tenant.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per tenant, by tenant. */
interface TenantSettingsRepository extends JpaRepository<TenantSettingsEntity, UUID> {
}
