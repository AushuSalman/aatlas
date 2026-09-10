package com.aatlas.decisions.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface QuoteRepository extends JpaRepository<QuoteEntity, UUID> {

    Optional<QuoteEntity> findByDecisionId(UUID decisionId);
}
