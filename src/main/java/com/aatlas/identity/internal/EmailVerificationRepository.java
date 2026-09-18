package com.aatlas.identity.internal;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Signup verification challenges. */
interface EmailVerificationRepository extends JpaRepository<EmailVerificationEntity, UUID> {

    /** For the per-address cap. Backed by {@code email_verifications_email_idx}. */
    long countByEmailNormalisedAndCreatedAtAfter(String emailNormalised, Instant since);
}
