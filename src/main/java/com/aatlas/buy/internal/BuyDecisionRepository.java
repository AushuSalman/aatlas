package com.aatlas.buy.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BuyDecisionRepository extends JpaRepository<BuyDecisionEntity, UUID> {
}
