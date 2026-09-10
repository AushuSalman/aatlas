package com.aatlas.analytics.internal.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** The procurement ledger. Every query carries {@code tenant_id} explicitly. */
public interface PurchaseOrderRepository
        extends JpaRepository<PurchaseOrderEntity, UUID>, JpaSpecificationExecutor<PurchaseOrderEntity> {

    boolean existsByTenantId(UUID tenantId);

    long countByTenantId(UUID tenantId);

    /** The whole tenant ledger, in {@code buildLedger()}'s exact stable order - see {@code seq}. */
    List<PurchaseOrderEntity> findByTenantIdOrderByOrderDateDescSeqAsc(UUID tenantId);

    Optional<PurchaseOrderEntity> findByTenantIdAndPoNumber(UUID tenantId, String poNumber);
}
