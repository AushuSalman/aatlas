package com.aatlas.integrations.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface IntegrationConnectionRepository extends JpaRepository<IntegrationConnectionEntity, UUID> {

    List<IntegrationConnectionEntity> findByTenantId(UUID tenantId);

    Optional<IntegrationConnectionEntity> findByTenantIdAndIntegrationKey(UUID tenantId, String integrationKey);

    void deleteByTenantIdAndIntegrationKey(UUID tenantId, String integrationKey);
}
