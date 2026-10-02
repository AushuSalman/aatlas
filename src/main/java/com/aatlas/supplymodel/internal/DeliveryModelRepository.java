package com.aatlas.supplymodel.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per tenant, looked up by the tenant. */
interface DeliveryModelRepository extends JpaRepository<DeliveryModelEntity, UUID> {
}
