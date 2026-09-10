package com.aatlas.buy.internal;

import com.aatlas.common.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Read-only access to the tenant's supplier panel: id, name, country, lead time, on-time
 * record and price index - the facts {@code buildBuyRecommendation} quotes every supplier
 * on.
 *
 * <p>The {@code suppliers} module (wave 1) already has this data in its {@code suppliers}
 * table, seeded from the same {@code seed/suppliers.json} used across the frontend and the
 * backend, but its only public (package-root) type is {@code SupplierPanelSeeder} - a
 * writer for seeding, not a reader. So, exactly as {@link CatalogGateway} does for
 * {@code catalog}, this reads the real {@code suppliers} table directly with plain SQL: the
 * live per-tenant panel (seeded eight plus anything added by a lookup), not a re-derivation
 * of the seeding arithmetic and not the static seed file.
 *
 * <p>TODO(merge): replace with a public reader on {@code suppliers} (e.g. a
 * {@code SupplierReader} in its package root) once one exists, and delete this class.
 */
@Component
public class SupplierGateway {

    private final JdbcTemplate jdbc;

    SupplierGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code SupplierRecord}'s buy-relevant fields: the ones {@code buildBuyRecommendation} quotes on. */
    public record SupplierRow(String id, String name, String country, int leadTimeDays, double otifPct, double priceIndex) {
    }

    /** The whole panel: the seeded eight plus anything added from a lookup. */
    public List<SupplierRow> panel() {
        UUID tenantId = TenantContext.requireTenantId();
        return jdbc.query("""
                select supplier_key, name, country, lead_time_days, otif_pct, price_index
                from suppliers where tenant_id = ? order by supplier_key
                """,
                (rs, i) -> new SupplierRow(
                        rs.getString("supplier_key"), rs.getString("name"), rs.getString("country"),
                        rs.getInt("lead_time_days"), rs.getDouble("otif_pct"), rs.getDouble("price_index")),
                tenantId);
    }
}
