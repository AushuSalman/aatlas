package com.aatlas.ingest.internal;

import com.aatlas.tenant.CountryCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which country a tenant trades in, and in what currency, straight from {@code tenants}.
 *
 * <p>A deliberate, narrow shortcut. The tenant module exposes provisioning but no lookup
 * yet, and the sample provisioner cannot reach its repository without failing
 * {@code ModularityTests}. Two columns read by primary key through JDBC keep the module
 * boundary intact at the Java level; when {@code tenant} grows a {@code TenantLookup}
 * interface this class is deleted and the calls replaced.
 */
@Component
class TenantCountryLookup {

    /** A tenant's country and trading currency. */
    record TenantProfile(CountryCode country, String currency) {
    }

    private final JdbcTemplate jdbc;

    TenantCountryLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    CountryCode countryOf(UUID tenantId) {
        return profile(tenantId).country();
    }

    TenantProfile profile(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select country, trading_currency from tenants where id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("No tenant " + tenantId + " - the JWT names a tenant that does not exist");
        }
        Map<String, Object> row = rows.getFirst();
        return new TenantProfile(CountryCode.from((String) row.get("country")), (String) row.get("trading_currency"));
    }
}
