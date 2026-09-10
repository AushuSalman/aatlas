package com.aatlas.analytics.internal.ledger;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.ingest.SampleDataConnected;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Writes the procurement ledger (~800 rows over 26 months) into {@code purchase_order} when
 * the sample data source connects, exactly like {@code suppliers}' {@code SuppliersSeedListener}
 * seeds the supplier panel from the same event - see that class for the pattern this follows.
 *
 * <p>Idempotent: a tenant that already has a ledger is left alone, so reconnecting the sample
 * source after a disconnect does not duplicate 800 rows.
 */
@Component
class ProcurementLedgerSeedListener {

    private static final Logger log = LoggerFactory.getLogger(ProcurementLedgerSeedListener.class);

    private final PurchaseOrderRepository purchaseOrders;
    private final AatlasClock clock;

    ProcurementLedgerSeedListener(PurchaseOrderRepository purchaseOrders, AatlasClock clock) {
        this.purchaseOrders = purchaseOrders;
        this.clock = clock;
    }

    // No @Transactional here: @ApplicationModuleListener already composes
    // @TransactionalEventListener with its own REQUIRES_NEW transaction, and stacking a
    // second (REQUIRED) @Transactional on the same method is what Spring refuses to start
    // with - see SuppliersSeedListener, which follows the same rule.
    @ApplicationModuleListener
    void on(SampleDataConnected event) {
        UUID tenantId = event.tenantId();
        if (purchaseOrders.existsByTenantId(tenantId)) {
            log.info("Procurement ledger already seeded for tenant {}", tenantId);
            return;
        }
        List<PoRow> rows = LedgerBuilder.build(clock.today());
        List<PurchaseOrderEntity> entities = rows.stream().map(PurchaseOrderEntity::new).toList();
        for (PurchaseOrderEntity e : entities) {
            e.setTenantId(tenantId);
        }
        purchaseOrders.saveAll(entities);
        log.info("Procurement ledger seeded for tenant {}: {} purchase orders", tenantId, entities.size());
    }
}
