package com.aatlas.identity.internal;

import com.aatlas.tenant.TenantDirectory;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Builds the session block that signup, login, refresh and {@code /me} all return, so
 * the four cannot drift apart in what they call the company or how they spell initials.
 */
@Component
class SessionViews {

    private final TenantDirectory tenants;
    private final DataSourceLookup dataSources;

    SessionViews(TenantDirectory tenants, DataSourceLookup dataSources) {
        this.tenants = tenants;
        this.dataSources = dataSources;
    }

    AuthResponse.UserView user(UserAccount user) {
        return new AuthResponse.UserView(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getTitle(),
                user.getSeatRole(),
                AuthResponse.initialsOf(user.getFullName()),
                user.getWorkspaceRole(),
                user.getPermissions());
    }

    AuthResponse.SessionView session(UserAccount user, Instant signedInAt, boolean isNewAccount) {
        return session(user, tenants.get(user.getTenantId()), signedInAt, isNewAccount);
    }

    /** For signup, which already holds the tenant it just created and must not re-read it mid-transaction. */
    AuthResponse.SessionView session(
            UserAccount user, TenantDirectory.TenantInfo tenant, Instant signedInAt, boolean isNewAccount) {
        return new AuthResponse.SessionView(
                user(user),
                tenant.name(),
                tenant.country(),
                tenant.tradingCurrency(),
                signedInAt,
                isNewAccount,
                dataSources.current(tenant.id()).orElse(null));
    }

    DataSourceView dataSource(java.util.UUID tenantId) {
        return dataSources.current(tenantId).orElse(null);
    }
}
