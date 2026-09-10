package com.aatlas.integrations.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface McpPermissionRepository extends JpaRepository<McpPermissionEntity, UUID> {

    List<McpPermissionEntity> findByTenantId(UUID tenantId);

    Optional<McpPermissionEntity> findByTenantIdAndPermissionKey(UUID tenantId, String permissionKey);
}
