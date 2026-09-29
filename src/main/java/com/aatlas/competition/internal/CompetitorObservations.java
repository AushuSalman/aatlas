package com.aatlas.competition.internal;

import com.aatlas.competition.internal.ShoppingProvider.Listing;
import com.aatlas.history.CompetitorPrices;
import com.aatlas.history.HistoryCaches;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code competitor_prices} as this module writes and lists it: one row per kept listing, the
 * provider as {@code source}, national (no region, no branch) because an online shelf price is
 * the same wherever the buyer is. The upsert key is the import's, so a second lookup the same
 * day replaces rather than duplicates. Every write evicts the tenant's history caches after
 * commit, so the next recommendation reads the new anchor.
 */
@Repository
class CompetitorObservations {

    private static final String UPSERT_SQL = """
            insert into competitor_prices (
                tenant_id, product_id, competitor, price, currency, region_key, store_id, observed_at, source_url, source)
            values (?, ?, ?, ?, ?, null, null, ?, ?, ?)
            on conflict (tenant_id, product_id, lower(competitor),
                         coalesce(store_id, '00000000-0000-0000-0000-000000000000'::uuid),
                         coalesce(region_key, ''), observed_at)
            do update set price = excluded.price, currency = excluded.currency, source_url = excluded.source_url,
                          source = excluded.source, import_batch_id = null, updated_at = now()
            """;

    private final JdbcTemplate jdbc;
    private final HistoryCaches caches;

    CompetitorObservations(JdbcTemplate jdbc, HistoryCaches caches) {
        this.jdbc = jdbc;
        this.caches = caches;
    }

    @Transactional
    int save(UUID tenantId, UUID productId, LocalDate today, List<Listing> kept) {
        if (kept.isEmpty()) {
            return 0;
        }
        List<Object[]> rows = new ArrayList<>();
        for (Listing l : kept) {
            rows.add(new Object[] {tenantId, productId, competitorName(l), l.price(), l.currency(), today,
                truncate(l.url(), 2000), l.provider()});
        }
        jdbc.batchUpdate(UPSERT_SQL, rows);
        caches.evictAfterCommit(tenantId);
        return rows.size();
    }

    /** The latest observation per competitor within the anchor window, with where it came from. */
    @Transactional(readOnly = true)
    List<Stored> forItem(UUID tenantId, UUID productId, LocalDate today) {
        return jdbc.query("""
                select distinct on (lower(competitor)) competitor, price, currency, observed_at, region_key,
                       source, source_url
                  from competitor_prices
                 where tenant_id = ? and product_id = ? and observed_at between ? and ?
                 order by lower(competitor), observed_at desc, created_at desc
                """, (rs, i) -> new Stored(rs.getString("competitor"), rs.getBigDecimal("price"),
                        rs.getString("currency"), rs.getObject("observed_at", LocalDate.class),
                        rs.getString("region_key"), rs.getString("source"), rs.getString("source_url")),
                tenantId, productId, today.minusDays(CompetitorPrices.MAX_AGE_DAYS), today);
    }

    record Stored(String competitor, java.math.BigDecimal price, String currency, LocalDate observedAt,
            String regionKey, String source, String url) {
    }

    /** The store's name; failing that the site's host; failing both, the provider's. */
    static String competitorName(Listing l) {
        String name = l.merchant();
        if (name == null || name.isBlank()) {
            try {
                name = URI.create(l.url()).getHost().replaceFirst("^www\\.", "");
            } catch (RuntimeException ex) {
                name = l.provider();
            }
        }
        return truncate(name.strip(), 120);
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
