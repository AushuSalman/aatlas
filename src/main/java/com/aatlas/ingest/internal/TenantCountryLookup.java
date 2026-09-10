package com.aatlas.ingest.internal;

import com.aatlas.tenant.CountryCode;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which country a tenant trades in, straight from {@code tenants.country}.
 *
 * <p>A deliberate, narrow shortcut. The tenant module exposes provisioning but no lookup
 * yet, and the sample provisioner cannot reach its repository without failing
 * {@code ModularityTests}. One column read by primary key through JDBC keeps the module
 * boundary intact at the Java level; when {@code tenant} grows a {@code TenantLookup}
 * interface this class is deleted and the call replaced.
 */
@Component
class TenantCountryLookup {

    private final JdbcTemplate jdbc;

    TenantCountryLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    CountryCode countryOf(UUID tenantId) {
        List<String> rows = jdbc.queryForList("select country from tenants where id = ?", String.class, tenantId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("No tenant " + tenantId + " - the JWT names a tenant that does not exist");
        }
        return CountryCode.from(rows.get(0));
    }
}
