package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.policy.PolicyReader;
import com.aatlas.tenant.TenantDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assembles {@code /me} from the user row, the tenant, the persona and the data source. */
@Service
class MeService {

    private final UserAccountRepository users;
    private final TenantDirectory tenants;
    private final PolicyReader policy;
    private final SessionViews sessions;

    MeService(UserAccountRepository users, TenantDirectory tenants, PolicyReader policy, SessionViews sessions) {
        this.users = users;
        this.tenants = tenants;
        this.policy = policy;
        this.sessions = sessions;
    }

    @Transactional(readOnly = true)
    MeResponse me(TenantContext.Actor actor) {
        UserAccount user = users.findById(actor.userId())
                // The token was signed for a user that is gone: 401 rather than 404, so the
                // client signs out instead of showing "user not found" on a page.
                .filter(u -> u.getTenantId().equals(actor.tenantId()))
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "unauthenticated", "Sign in to continue."));

        TenantDirectory.TenantInfo tenant = tenants.get(actor.tenantId());
        // The persona is looked up by the user's stored seat, not the token's claim, so a
        // seat change takes effect on the next request rather than the next sign-in.
        return new MeResponse(
                sessions.user(user),
                tenant.name(),
                tenant.country(),
                tenant.tradingCurrency(),
                policy.personaFor(tenant.id(), user.getSeatRole().wireValue()),
                sessions.dataSource(tenant.id()));
    }
}
