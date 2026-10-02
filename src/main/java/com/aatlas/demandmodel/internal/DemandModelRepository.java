package com.aatlas.demandmodel.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per tenant, looked up by the tenant. */
interface DemandModelRepository extends JpaRepository<DemandModelEntity, UUID> {
}
