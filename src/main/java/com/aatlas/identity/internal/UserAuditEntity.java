package com.aatlas.identity.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One line of the Users screen's activity log. Names are copied in, so it reads correctly after a removal. */
@Entity
@Table(name = "user_audit")
public class UserAuditEntity extends TenantScopedEntity {

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "actor_name", nullable = false, updatable = false)
    private String actorName;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "target", nullable = false, updatable = false)
    private String target;

    @Column(name = "detail", nullable = false, updatable = false)
    private String detail;

    protected UserAuditEntity() {
        // JPA
    }

    UserAuditEntity(UUID tenantId, UUID actorId, String actorName, String action, String target, String detail) {
        setTenantId(tenantId);
        this.actorId = actorId;
        this.actorName = actorName;
        this.action = action;
        this.target = target;
        this.detail = detail;
    }

    public String getActorName() {
        return actorName;
    }

    public String getAction() {
        return action;
    }

    public String getTarget() {
        return target;
    }

    public String getDetail() {
        return detail;
    }
}
