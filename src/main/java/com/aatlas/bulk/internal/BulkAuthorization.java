package com.aatlas.bulk.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Applying a bulk strategy is gated on the seat's {@code Persona.bulk} flag - "bulk
 * repricing and basket awards" is exactly what that flag exists to describe. Reading a
 * plan is not gated: exploring what a strategy would do costs nothing and every seat that
 * can open the bulk screens should be able to.
 */
@Component
class BulkAuthorization {

    private final PolicyReader policy;

    BulkAuthorization(PolicyReader policy) {
        this.policy = policy;
    }

    void requireBulkSeat() {
        TenantContext.Actor actor = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("No actor bound"));
        Persona persona = policy.personaFor(actor.tenantId(), actor.role());
        if (!persona.bulk()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed",
                    "This seat may not apply a bulk strategy.");
        }
    }
}
