package com.aatlas.identity.internal;

import com.aatlas.policy.Persona;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * What one person may open and do, when an admin has set it in place of their job
 * function's defaults. Field for field the frontend's {@code UserPermissions}; stored as
 * {@code users.permissions} jsonb.
 *
 * @param modules the workspaces they can open, in the rail's vocabulary
 * @param approveLimit largest order awarded alone, USD; null is no limit, 0 sends every order for approval
 */
record UserPermissions(List<String> modules, boolean bulk, boolean guardrails, BigDecimal approveLimit) {

    /** Every module there is, in the rail's order. */
    static final List<String> MODULE_ORDER = List.of("sell", "buy", "suppliers", "insights", "stores", "products", "history");

    static final Set<String> KNOWN = Set.copyOf(MODULE_ORDER);

    UserPermissions {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }

    static UserPermissions of(Persona persona) {
        return new UserPermissions(persona.modules(), persona.bulk(), persona.guardrails(), persona.approveLimit());
    }
}
