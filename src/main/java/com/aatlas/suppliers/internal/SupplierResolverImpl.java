package com.aatlas.suppliers.internal;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.suppliers.SupplierResolver;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link SupplierResolver} over the {@code suppliers} table.
 *
 * <p>JDBC rather than the entity: the callers are bulk loaders that resolve thousands of
 * rows, and they run inside their own transaction ({@link Propagation#MANDATORY}) so a failed
 * load takes the suppliers it created back with it.
 */
@Service
class SupplierResolverImpl implements SupplierResolver {

    private final JdbcTemplate jdbc;
    private final AatlasClock clock;

    SupplierResolverImpl(JdbcTemplate jdbc, AatlasClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SupplierRef> find(UUID tenantId, String codeOrName) {
        if (codeOrName == null || codeOrName.isBlank()) {
            return Optional.empty();
        }
        String key = codeOrName.strip().toLowerCase(Locale.ROOT);
        return jdbc.query("""
                select id, supplier_key, name, country
                  from suppliers
                 where tenant_id = ?
                   and (lower(supplier_key) = ? or lower(vendor_code) = ? or lower(btrim(name)) = ?)
                 order by is_custom asc, created_at asc
                 limit 1
                """, rs -> rs.next()
                        ? Optional.of(new SupplierRef(rs.getObject("id", UUID.class), rs.getString("supplier_key"),
                                rs.getString("name"), rs.getString("country")))
                        : Optional.<SupplierRef>empty(),
                tenantId, key, key, key);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SupplierRef create(UUID tenantId, String name, String countryRaw, String source, UUID importBatchIdOrNull) {
        String cleanName = name.strip();
        String country = countryRaw == null || countryRaw.isBlank() ? "Unknown" : canonicalCountry(countryRaw);
        String supplierKey = "po-" + fnvBase36(cleanName.toLowerCase(Locale.ROOT) + "|" + country.toLowerCase(Locale.ROOT));
        String vendorCode = "V-" + (Math.floorMod(supplierKey.hashCode(), 9000) + 1000);

        UUID id = jdbc.queryForObject("""
                insert into suppliers (
                    tenant_id, supplier_key, vendor_code, name, country, city, website, category,
                    contact_name, email, currency,
                    lead_time_days, otif_pct, price_index, defect_pct, holds_stock, years_trading,
                    spend_share_12m, spend_ytd, po_count_12m, is_custom, added_by, added_at, since,
                    source, import_batch_id)
                values (?, ?, ?, ?, ?, '', '', null, '', '', ?,
                        null, null, null, null, null, null,
                        0, 0, 0, true, null, ?, null, ?, ?)
                on conflict (tenant_id, supplier_key) do update set name = excluded.name
                returning id
                """, UUID.class,
                tenantId, supplierKey, vendorCode, cleanName, country, Currencies.forCountry(country),
                java.sql.Timestamp.from(clock.now()), source, importBatchIdOrNull);
        return new SupplierRef(id, supplierKey, cleanName, country);
    }

    @Override
    public String canonicalCountry(String raw) {
        return Countries.canonicalOr(raw, raw == null ? "" : raw.strip());
    }

    @Override
    public boolean hasLane(String canonicalCountry) {
        return Countries.hasLane(canonicalCountry);
    }

    /** FNV-1a over the key, in base 36: short, stable, and never the frontend's seeded namespace. */
    private static String fnvBase36(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return Integer.toUnsignedString(h, 36);
    }
}
