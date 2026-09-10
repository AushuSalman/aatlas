package com.aatlas.assistant.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface AssistantQuestionRepository extends JpaRepository<AssistantQuestionEntity, UUID> {

    List<AssistantQuestionEntity> findByTenantIdAndUserIdOrderByAskedAtDesc(UUID tenantId, UUID userId,
            Pageable pageable);
}
