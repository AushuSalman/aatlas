package com.aatlas.common.tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * The tenant and actor for the current request or job.
 *
 * <p>Set once per request from the JWT (never from the URL or a header the caller
 * controls) and read by the persistence layer, which pushes it onto the JDBC
 * connection as {@code app.tenant_id} so PostgreSQL row-level security can enforce
 * isolation even when a query forgets its {@code tenant_id} predicate.
 *
 * <p>Backed by an inheritable holder so work handed to a virtual-thread executor
 * inside a request keeps the same tenant. Background jobs set it explicitly with
 * {@link #runAs}.
 */
public final class TenantContext {

    private static final ThreadLocal<Actor> CURRENT = new InheritableThreadLocal<>();

    private TenantContext() {
    }

    /** The authenticated caller: which tenant, which user, which seat. */
    public record Actor(UUID tenantId, UUID userId, String role) {

        public static Actor system(UUID tenantId) {
            return new Actor(tenantId, null, "system");
        }
    }

    public static void set(Actor actor) {
        CURRENT.set(actor);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<Actor> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The tenant, or a failure: call this where a tenant is genuinely required. */
    public static UUID requireTenantId() {
        return current()
                .map(Actor::tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "No tenant bound to the current thread. A request must carry a JWT; "
                                + "a job must wrap its work in TenantContext.runAs(...)."));
    }

    public static Optional<UUID> currentUserId() {
        return current().map(Actor::userId);
    }

    /** Runs {@code work} as {@code actor}, restoring whatever was bound before. */
    public static <T> T runAs(Actor actor, java.util.function.Supplier<T> work) {
        Actor previous = CURRENT.get();
        CURRENT.set(actor);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void runAs(Actor actor, Runnable work) {
        runAs(actor, () -> {
            work.run();
            return null;
        });
    }
}
