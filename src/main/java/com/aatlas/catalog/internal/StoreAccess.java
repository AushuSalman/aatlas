package com.aatlas.catalog.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.policy.PolicyReader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who may change the branch list.
 *
 * <p>The heads of sales and purchasing and the commercial director - the same seats that
 * may rename the company, and for the same reason. A branch is not a working document: it
 * decides which market a price is computed in, which rollup a number lands in and where a
 * dot appears on the map, so every seat below reads it and none of them edits it.
 *
 * <p>Extracted so create, edit and delete enforce one rule from one place rather than
 * three copies that drift.
 */
@Component
class StoreAccess {

    private final PolicyReader policy;

    StoreAccess(PolicyReader policy) {
        this.policy = policy;
    }

    /**
     * @throws ApiException 403 not_allowed if this seat may not change the branch list
     */
    void requireHeadOrDirector() {
        String role = TenantContext.current().map(TenantContext.Actor::role).orElseThrow(StoreAccess::notAllowed);
        if (!policy.personaFor(TenantContext.requireTenantId(), role).isHeadOrDirector()) {
            throw notAllowed();
        }
    }

    static ApiException notAllowed() {
        return new ApiException(HttpStatus.FORBIDDEN, "not_allowed",
                "Only heads of sales and purchasing and the commercial director can change the branch list.");
    }
}
