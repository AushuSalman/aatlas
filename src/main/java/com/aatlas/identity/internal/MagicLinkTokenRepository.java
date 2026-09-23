package com.aatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface MagicLinkTokenRepository extends JpaRepository<MagicLinkTokenEntity, UUID> {

    Optional<MagicLinkTokenEntity> findByTokenHash(byte[] tokenHash);
}
