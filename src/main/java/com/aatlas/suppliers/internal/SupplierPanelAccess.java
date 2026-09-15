package com.aatlas.suppliers.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who may change the supplier panel.
 *
 * <p>Buy seats and the commercial director. Finance reads the panel and the sell side does not
 * see it at all, which is a seat rule rather than a screen rule - adding a supplier commits the
 * business to a counterparty, and that is a procurement decision wherever it is made from.
 *
 * <p>Extracted so the lookup path and the CSV import enforce the same rule from one place. Two
 * copies of an authorisation check is how one of them ends up a version behind.
 */
@Component
class SupplierPanelAccess {

    private final PolicyReader policy;

    SupplierPanelAccess(PolicyReader policy) {
        this.policy = policy;
    }

    /**
     * @throws ApiException 403 if this seat may not change the panel
     */
    void requireBuySeatOrDirector() {
        String role = TenantContext.current().map(TenantContext.Actor::role).orElseThrow(SupplierPanelAccess::notAllowed);

        // The director sits on both sides, so the persona's single "side" cannot express it.
        if ("both".equals(role)) {
            return;
        }
        Persona persona = policy.personaFor(TenantContext.requireTenantId(), role);
        if (persona.side() != Persona.Side.BUY) {
            throw notAllowed();
        }
    }

    static ApiException notAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, "not_allowed", "This seat may not perform that action.");
    }
}
