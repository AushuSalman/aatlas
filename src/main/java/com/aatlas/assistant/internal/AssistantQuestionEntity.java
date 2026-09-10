package com.aatlas.assistant.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One question asked of Ask Aatlas. Backs {@code GET /assistant/history}. */
@Entity
@Table(name = "assistant_question")
public class AssistantQuestionEntity extends TenantScopedEntity {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "question", nullable = false, updatable = false)
    private String question;

    @Column(name = "asked_at", nullable = false, updatable = false)
    private Instant askedAt;

    protected AssistantQuestionEntity() {
        // JPA
    }

    AssistantQuestionEntity(UUID tenantId, UUID userId, String question, Instant askedAt) {
        setTenantId(tenantId);
        this.userId = userId;
        this.question = question;
        this.askedAt = askedAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getQuestion() {
        return question;
    }

    public Instant getAskedAt() {
        return askedAt;
    }
}
