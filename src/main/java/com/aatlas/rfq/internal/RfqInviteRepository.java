package com.aatlas.rfq.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RfqInviteRepository extends JpaRepository<RfqInviteEntity, UUID> {

    List<RfqInviteEntity> findByTenantIdAndRfqId(UUID tenantId, UUID rfqId);
}
