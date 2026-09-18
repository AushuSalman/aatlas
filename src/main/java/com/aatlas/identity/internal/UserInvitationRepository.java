package com.aatlas.identity.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Invitation links. Backed by {@code user_invitations_hash_uk}. */
interface UserInvitationRepository extends JpaRepository<UserInvitationEntity, UUID> {

    Optional<UserInvitationEntity> findByTokenHash(byte[] tokenHash);

    List<UserInvitationEntity> findByUserId(UUID userId);
}
