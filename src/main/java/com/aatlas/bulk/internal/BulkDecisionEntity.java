package com.aatlas.bulk.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * One "apply a bulk strategy". {@code TODO(merge): replace with the decisions module's
 * ledger} - see {@code com.aatlas.bulk.DecisionRecorder} for why this table exists here.
 */
@Entity
@Table(name = "bulk_decision")
public class BulkDecisionEntity extends TenantScopedEntity {

    /** {@code sell} or {@code buy}. */
    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "strategy_key", nullable = false, updatable = false)
    private String strategyKey;

    /** The store code for a sell decision, the region key for a buy decision. */
    @Column(name = "target", nullable = false, updatable = false)
    private String target;

    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Column(name = "total_value", nullable = false, updatable = false, precision = 14, scale = 4)
    private BigDecimal totalValue;

    @Column(name = "total_impact", nullable = false, updatable = false, precision = 14, scale = 4)
    private BigDecimal totalImpact;

    @Type(JsonType.class)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    protected BulkDecisionEntity() {
        // JPA
    }

    BulkDecisionEntity(UUID tenantId, String kind, String strategyKey, String target, int itemCount,
            BigDecimal totalValue, BigDecimal totalImpact, Map<String, Object> payload, UUID createdBy) {
        setTenantId(tenantId);
        this.kind = kind;
        this.strategyKey = strategyKey;
        this.target = target;
        this.itemCount = itemCount;
        this.totalValue = totalValue;
        this.totalImpact = totalImpact;
        this.payload = payload;
        this.createdBy = createdBy;
    }

    public String getKind() {
        return kind;
    }

    public String getStrategyKey() {
        return strategyKey;
    }

    public String getTarget() {
        return target;
    }

    public int getItemCount() {
        return itemCount;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public BigDecimal getTotalImpact() {
        return totalImpact;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }
}
