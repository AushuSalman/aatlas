package com.aatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Linked provider accounts. Backed by {@code user_identities_subject_uk}. */
interface UserIdentityRepository extends JpaRepository<UserIdentityEntity, UUID> {

    Optional<UserIdentityEntity> findByProviderAndSubject(String provider, String subject);

    boolean existsByUserIdAndProvider(UUID userId, String provider);
}
