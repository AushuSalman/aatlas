package com.aatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Users by normalised email.
 *
 * <p>Not tenant-scoped, and that is deliberate rather than an oversight: sign-in has only
 * an email to go on and must find the account before it can know the tenant. Every other
 * caller reaches a user through the tenant it already holds.
 */
interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByEmailNormalised(String emailNormalised);

    boolean existsByEmailNormalised(String emailNormalised);
}
