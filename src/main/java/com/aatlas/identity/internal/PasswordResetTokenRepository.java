package com.aatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reset tokens by hash. Backed by {@code password_reset_tokens_hash_uk}. */
interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {

    Optional<PasswordResetTokenEntity> findByTokenHash(byte[] tokenHash);
}
