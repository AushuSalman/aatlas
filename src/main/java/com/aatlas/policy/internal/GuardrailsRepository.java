package com.aatlas.policy.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** One row per tenant, looked up by the tenant. */
interface GuardrailsRepository extends JpaRepository<GuardrailsEntity, UUID> {
}
