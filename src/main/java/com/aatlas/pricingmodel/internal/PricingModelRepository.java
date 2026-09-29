package com.aatlas.pricingmodel.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per tenant, looked up by the tenant. */
interface PricingModelRepository extends JpaRepository<PricingModelEntity, UUID> {
}
