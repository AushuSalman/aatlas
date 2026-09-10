package com.aatlas.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Every tenant is un-onboarded until the {@code integrations} module lands.
 *
 * <p>TODO(integrations): when the {@code data_sources} table (V7, integrations module)
 * exists in this branch, replace this bean with one that calls that module's package-root
 * reader - something like {@code DataSources.currentFor(tenantId)} returning kind, label,
 * detail and connectedAt - and delete this class. {@code MeService} and
 * {@code SessionViews} already take the lookup through the interface, so nothing else
 * changes. Do not query the table from here: {@code ModularityTests} would fail the
 * build, and rightly so.
 */
@Component
class NoDataSourceYet implements DataSourceLookup {

    @Override
    public Optional<DataSourceView> current(UUID tenantId) {
        return Optional.empty();
    }
}
