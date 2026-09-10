package com.aatlas.catalog.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Accounts, per tenant. */
interface CustomerRepository extends JpaRepository<CustomerEntity, UUID>, JpaSpecificationExecutor<CustomerEntity> {

    long countByTenantId(UUID tenantId);

    Optional<CustomerEntity> findByTenantIdAndCode(UUID tenantId, String code);

    Optional<CustomerEntity> findByTenantIdAndId(UUID tenantId, UUID id);
}
