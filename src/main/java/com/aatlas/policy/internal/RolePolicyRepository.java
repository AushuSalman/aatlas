package com.aatlas.policy.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** The defaults plus one tenant's overrides, in one round trip. */
interface RolePolicyRepository extends JpaRepository<RolePolicyEntity, UUID> {

    @Query("select p from RolePolicyEntity p where p.tenantId is null or p.tenantId = :tenantId")
    List<RolePolicyEntity> findDefaultsAndOverridesFor(@Param("tenantId") UUID tenantId);
}
