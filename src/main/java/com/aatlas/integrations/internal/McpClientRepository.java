package com.aatlas.integrations.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface McpClientRepository extends JpaRepository<McpClientEntity, UUID> {

    List<McpClientEntity> findByTenantId(UUID tenantId);

    Optional<McpClientEntity> findByTenantIdAndClientKey(UUID tenantId, String clientKey);
}
