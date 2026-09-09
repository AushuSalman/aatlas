package com.aatlas.identity.internal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Refresh tokens by hash, plus the two bulk revocations that matter. */
interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    /** The lookup every refresh performs. Backed by {@code refresh_tokens_hash_uk}. */
    Optional<RefreshTokenEntity> findByTokenHash(byte[] tokenHash);

    /** Live tokens for one user, newest first: the "signed-in devices" list. */
    List<RefreshTokenEntity> findByUserIdAndRevokedAtIsNullOrderByIssuedAtDesc(UUID userId);

    /**
     * Revokes every live token a user holds.
     *
     * <p>Used for sign-out-everywhere, a password change, and - the case that matters -
     * when a spent token is presented a second time. A bulk update rather than a
     * read-modify-write because at that moment a credential is known to be loose and the
     * gap between loading rows and saving them is exactly what an attacker is racing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update RefreshTokenEntity t
               set t.revokedAt = :now,
                   t.revokedReason = :reason
             where t.userId = :userId
               and t.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now, @Param("reason") String reason);

    /**
     * Deletes tokens that expired before {@code cutoff}.
     *
     * <p>Spent and revoked rows are kept until they expire because reuse detection needs
     * to tell "this token was already used" apart from "this token was never issued".
     * After expiry they can no longer be exchanged, so they are only taking up space.
     */
    @Modifying
    @Query("delete from RefreshTokenEntity t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
