package com.aatlas.suppliers.internal;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records which of a tenant's suppliers can quote on which of its products.
 *
 * <p>The sample dataset has no opinion on this. {@code seed/suppliers.json} is a flat panel
 * of eight and {@code seed/products.json} a flat list of items; nothing in either says that
 * Cascade Copper Mills sells the copper coil and not the ball valve. The prototype these
 * engines were ported from behaved accordingly - every supplier quoted on everything - and
 * the golden files the buy engines are pinned against record exactly that.
 *
 * <p>So the seed writes the relation it can honestly claim: <b>every seeded supplier
 * against every seeded product</b>. That reproduces the old behaviour row for row, which is
 * the point - {@code supplier_products} becomes the single place the panel is decided
 * without changing what the panel currently is. Narrowing an item is then a data act
 * (delete the links that do not apply, or import a real price list), not a code change, and
 * {@code SupplierGateway.panelFor} already honours whatever it finds.
 *
 * <p>Eight suppliers against the sample's fifteen products is 120 rows. Against an imported
 * catalogue it is eight times the item count, which is why this runs only for the sample
 * source: a real catalogue should get a real price list instead.
 *
 * <p>Reads {@code products} - {@code catalog}'s table - directly, the same gateway-style
 * stand-in {@code buy.internal.CatalogGateway} uses and for the same reason: {@code
 * catalog} exposes no public reader. It creates no Java dependency on {@code
 * catalog.internal}, so {@code ModularityTests} is unaffected.
 */
@Component
class SupplierProductLinkSeeder {

    private final JdbcTemplate jdbc;

    SupplierProductLinkSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Links every supplier to every product for one tenant, and returns the rows written.
     *
     * <p>Idempotent: {@code ON CONFLICT DO NOTHING} against the pair's unique index, so a
     * re-seed after a catalogue import adds only what is new and a replayed event writes
     * nothing. Set-based rather than a loop - it is one statement either way, and the row
     * count is the answer.
     *
     * <p>Runs on the Modulith worker thread, where {@code AsyncConfig}'s task decorator has
     * rebound the tenant, so {@code app.tenant_id} is set and the row-level security policy
     * on all three tables is satisfied. {@code tenant_id} is still passed explicitly rather
     * than leant on: the predicate is what makes the statement correct, and RLS is the
     * backstop.
     */
    int linkAllForTenant(UUID tenantId) {
        return jdbc.update("""
                insert into supplier_products (tenant_id, supplier_id, product_id)
                select ?, s.id, p.id
                from suppliers s
                cross join products p
                where s.tenant_id = ? and p.tenant_id = ?
                on conflict (tenant_id, supplier_id, product_id) do nothing
                """, tenantId, tenantId, tenantId);
    }
}
