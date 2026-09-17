package com.aatlas.buy.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class BuyDecisionRecorderStandIn implements DecisionRecorder {

    private final BuyDecisionRepository repository;
    private final AatlasClock clock;

    BuyDecisionRecorderStandIn(BuyDecisionRepository repository, AatlasClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BuyDecisionEntity record(String itemNumber, String regionKey, String destinationStoreCode,
            String optionKey, String supplierId, Integer qty, BigDecimal orderValue, String status,
            String approverRole, BigDecimal approveLimit) {
        BuyDecisionEntity entity = new BuyDecisionEntity(itemNumber, regionKey, destinationStoreCode, optionKey,
                supplierId, qty, orderValue, status, TenantContext.currentUserId().orElse(null), clock.now(),
                approverRole, approveLimit);
        entity.setTenantId(TenantContext.requireTenantId());
        return repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BuyDecisionEntity> findByDecisionId(UUID decisionId) {
        return repository.findByTenantIdAndDecisionId(TenantContext.requireTenantId(), decisionId);
    }

    @Override
    @Transactional
    public void save(BuyDecisionEntity entity) {
        repository.save(entity);
    }
}
