package com.aatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** SSO tickets by hash. Backed by {@code sso_tickets_hash_uk}. */
interface SsoTicketRepository extends JpaRepository<SsoTicketEntity, UUID> {

    Optional<SsoTicketEntity> findByTokenHash(byte[] tokenHash);
}
