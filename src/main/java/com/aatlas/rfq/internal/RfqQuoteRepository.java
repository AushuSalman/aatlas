package com.aatlas.rfq.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RfqQuoteRepository extends JpaRepository<RfqQuoteEntity, UUID> {

    List<RfqQuoteEntity> findByTenantIdAndRfqId(UUID tenantId, UUID rfqId);

    Optional<RfqQuoteEntity> findByTenantIdAndRfqIdAndSupplierId(UUID tenantId, UUID rfqId, String supplierId);
}
