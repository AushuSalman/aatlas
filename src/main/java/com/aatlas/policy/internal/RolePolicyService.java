package com.aatlas.policy.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merges the platform defaults with a tenant's overrides.
 *
 * <p>An override row replaces the default for that seat wholesale rather than field by
 * field: a tenant that wants only a different buyer limit gets a full row copied from the
 * default with one number changed, which keeps "what does this tenant's buyer see" a
 * single-row answer with no inheritance to reason about.
 */
@Service
class RolePolicyService implements PolicyReader {

    /** The order the seat picker shows them, from the frontend's {@code PERSONAS}. */
    static final List<String> ROLE_ORDER = List.of(
            "sales-rep", "seller", "sales-head",
            "purchase-manager", "buyer", "purchase-head",
            "finance", "both");

    private final RolePolicyRepository rolePolicies;

    RolePolicyService(RolePolicyRepository rolePolicies) {
        this.rolePolicies = rolePolicies;
    }

    @Override
    @Transactional(readOnly = true)
    public Persona personaFor(UUID tenantId, String role) {
        Map<String, Persona> merged = merged(tenantId);
        Persona persona = role == null ? null : merged.get(role.strip());
        if (persona == null) {
            throw ApiException.badRequest("unknown_role", "'" + role + "' is not a seat. Expected one of " + ROLE_ORDER);
        }
        return persona;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Persona> personasFor(UUID tenantId) {
        return List.copyOf(merged(tenantId).values());
    }

    private Map<String, Persona> merged(UUID tenantId) {
        Map<String, RolePolicyEntity> rows = new LinkedHashMap<>();
        // Defaults first, then overrides, so a tenant row replaces the default for its seat.
        List<RolePolicyEntity> all = rolePolicies.findDefaultsAndOverridesFor(tenantId);
        for (String role : ROLE_ORDER) {
            all.stream()
                    .filter(r -> r.getRole().equals(role))
                    .filter(r -> r.getTenantId() == null)
                    .findFirst()
                    .ifPresent(r -> rows.put(role, r));
            all.stream()
                    .filter(r -> r.getRole().equals(role))
                    .filter(r -> r.getTenantId() != null)
                    .findFirst()
                    .ifPresent(r -> rows.put(role, r));
        }
        if (rows.size() != ROLE_ORDER.size()) {
            // The V5 seed guarantees eight defaults; fewer means someone deleted a row.
            throw new IllegalStateException(
                    "role_policy holds " + rows.size() + " of " + ROLE_ORDER.size() + " seats for tenant " + tenantId);
        }

        Map<String, Persona> personas = new LinkedHashMap<>();
        rows.forEach((role, row) -> {
            RolePolicyEntity approver = row.getApproverRole() == null ? null : rows.get(row.getApproverRole());
            personas.put(role, row.toPersona(approver == null ? null : approver.toPersona(null).title()));
        });
        return personas;
    }
}
