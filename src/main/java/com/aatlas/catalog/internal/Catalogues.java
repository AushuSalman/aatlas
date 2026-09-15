package com.aatlas.catalog.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;

/**
 * The three rules every catalogue read shares, in one place.
 *
 * <p>They were private to {@link CatalogService} while it was the only reader. {@link
 * StoreService} now answers the same questions about the same tables, and an
 * authorisation-shaped rule ("a tenant with no catalogue reads nothing") kept in two
 * copies is how one of them ends up a version behind.
 */
final class Catalogues {

    private Catalogues() {
    }

    /** The tenant from the JWT, or 404 no_catalogue if it has nothing to read yet. */
    static UUID requireCatalogue(StoreRepository stores) {
        UUID tenantId = TenantContext.requireTenantId();
        if (!stores.existsByTenantId(tenantId)) {
            throw noCatalogue();
        }
        return tenantId;
    }

    static ApiException noCatalogue() {
        return new ApiException(HttpStatus.NOT_FOUND, "no_catalogue",
                "This workspace has no catalogue yet. Connect a data source with "
                        + "POST /api/v1/data-sources - {\"kind\":\"sample\"} is the quickest way to see "
                        + "every screen - or import history with POST /api/v1/imports.");
    }

    /**
     * The frontend addresses branches and accounts by their codes ({@code 100959},
     * {@code c-1}); a generic client may use our uuid. Codes win, and a value that is not
     * a code is only then tried as a uuid.
     */
    static <T> Optional<T> findByCodeOrId(
            String idOrCode, Function<String, Optional<T>> byCode, Function<UUID, Optional<T>> byId) {
        String value = idOrCode == null ? "" : idOrCode.strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Optional<T> found = byCode.apply(value);
        if (found.isPresent()) {
            return found;
        }
        try {
            return byId.apply(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /** Makes a user's {@code %} or {@code _} literal in a {@code like} pattern. */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
