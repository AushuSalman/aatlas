package com.aatlas.ingest.internal;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.ValidationContext;
import com.aatlas.ingest.internal.csv.ValueParsers.DateOrder;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link ValidationContext} for one tenant and kind: currency, date order, today,
 * and the small reference sets a kind's rules consult (known items, branch codes,
 * subdivision codes, tracked commodities).
 */
@Component
class ValidationContexts {

    /** The Hardin sample is written in dollars whatever the tenant trades in. */
    static final String SAMPLE_CURRENCY = "USD";

    private final JdbcTemplate jdbc;
    private final TenantCountryLookup tenants;
    private final AatlasClock clock;

    ValidationContexts(JdbcTemplate jdbc, TenantCountryLookup tenants, AatlasClock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.clock = clock;
    }

    /**
     * @param source {@code sample} validates the file in its own currency (USD); the loader
     *     converts. Anything else validates in the tenant's trading currency.
     */
    ValidationContext forBatch(UUID tenantId, ImportKind kind, String source) {
        TenantCountryLookup.TenantProfile profile = tenants.profile(tenantId);
        String currency = "sample".equals(source) ? SAMPLE_CURRENCY : profile.currency();
        DateOrder order = DateOrder.forCountry(profile.country().name());

        Set<String> knownItems = Set.of();
        Set<String> commodities = null;
        Set<String> knownBranches = Set.of();
        Set<String> regionCodes = Set.of();
        if (kind == ImportKind.PRODUCTS) {
            knownItems = lower(jdbc.queryForList(
                    "select item_number from products where tenant_id = ?", String.class, tenantId));
            commodities = lower(jdbc.queryForList("select commodity_key from commodities", String.class));
            if (commodities.isEmpty()) {
                commodities = null;
            }
        }
        if (kind == ImportKind.COMPETITOR_PRICES) {
            knownBranches = lower(jdbc.queryForList(
                    "select store_code from stores where tenant_id = ?", String.class, tenantId));
            regionCodes = lower(jdbc.queryForList(
                    "select code from subdivisions where country_code = ?", String.class, profile.country().name()));
        }
        return new ValidationContext(currency, order, clock.today(), knownItems, knownBranches, regionCodes,
                commodities);
    }

    /** The context a loader writes with: always the tenant's own currency. */
    ValidationContext forLoad(UUID tenantId, ImportKind kind) {
        return forBatch(tenantId, kind, "upload");
    }

    private static Set<String> lower(Iterable<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) {
            if (v != null) {
                out.add(v.strip().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}
