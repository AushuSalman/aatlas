package com.aatlas.ingest.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** A tenant's sources. Every query carries the tenant; RLS is the second line. */
interface DataSourceRepository extends JpaRepository<DataSourceEntity, UUID> {

    List<DataSourceEntity> findByTenantIdOrderByConnectedAtDesc(UUID tenantId);

    Optional<DataSourceEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<DataSourceEntity> findFirstByTenantIdAndKind(UUID tenantId, DataSourceKind kind);
}
