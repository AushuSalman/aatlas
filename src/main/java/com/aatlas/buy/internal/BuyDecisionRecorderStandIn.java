package com.aatlas.buy.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import java.math.BigDecimal;
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
    public void record(String itemNumber, String regionKey, String destinationStoreCode, String optionKey,
            BigDecimal orderValue, String status, String approverRole, BigDecimal approveLimit) {
        BuyDecisionEntity entity = new BuyDecisionEntity(itemNumber, regionKey, destinationStoreCode, optionKey,
                orderValue, status, TenantContext.currentUserId().orElse(null), clock.now(), approverRole,
                approveLimit);
        entity.setTenantId(TenantContext.requireTenantId());
        repository.save(entity);
    }
}
