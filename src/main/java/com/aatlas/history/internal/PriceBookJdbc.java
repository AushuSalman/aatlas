package com.aatlas.history.internal;

import com.aatlas.history.HistoryCaches;
import com.aatlas.history.PriceBook;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only writes to {@code product_prices}. Every row of one call carries the same
 * {@code write_id}, which is what makes the call undoable; the caches are dropped once the
 * transaction commits so the next read sees the new price.
 */
@Repository
class PriceBookJdbc implements PriceBook {

    private static final String INSERT = """
            INSERT INTO product_prices (tenant_id, product_id, store_id, list_price, cost, currency, effective_from,
                                        source, basis, set_by, write_id, import_batch_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """;

    /** Rows of the write that no newer row for the same (product, store) has superseded. */
    private static final String UNDO = """
            DELETE FROM product_prices p
             WHERE p.tenant_id = ? AND p.write_id = ?
               AND NOT EXISTS (SELECT 1 FROM product_prices n
                                WHERE n.tenant_id = p.tenant_id AND n.product_id = p.product_id
                                  AND n.store_id IS NOT DISTINCT FROM p.store_id
                                  AND n.created_at > p.created_at)
            """;

    private final JdbcTemplate jdbc;
    private final HistoryCaches caches;
    private final ObjectMapper json;

    PriceBookJdbc(JdbcTemplate jdbc, HistoryCaches caches, ObjectMapper json) {
        this.jdbc = jdbc;
        this.caches = caches;
        this.json = json;
    }

    @Override
    @Transactional
    public WriteResult write(List<PriceWrite> rows, String source, UUID setBy, UUID importBatchIdOrNull) {
        UUID tenantId = Sql.tenant();
        UUID writeId = UUID.randomUUID();
        String currency = currency(tenantId);
        Map<String, UUID> products = index("SELECT item_number AS k, id FROM products WHERE tenant_id = ?", tenantId);
        Map<String, UUID> stores = index("SELECT store_code AS k, id FROM stores WHERE tenant_id = ?", tenantId);

        List<Object[]> batch = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (PriceWrite row : rows) {
            UUID productId = row.itemNumber() == null ? null : products.get(key(row.itemNumber()));
            if (productId == null) {
                skipped.add(new Skipped(row.itemNumber(), row.storeCode(), "unknown_item"));
                continue;
            }
            UUID storeId = null;
            if (row.storeCode() != null && !row.storeCode().isBlank()) {
                storeId = stores.get(key(row.storeCode()));
                if (storeId == null) {
                    skipped.add(new Skipped(row.itemNumber(), row.storeCode(), "unknown_store"));
                    continue;
                }
            }
            if (row.listPrice() == null && row.cost() == null) {
                skipped.add(new Skipped(row.itemNumber(), row.storeCode(), "no_value"));
                continue;
            }
            batch.add(new Object[] {
                tenantId, productId, storeId, row.listPrice(), row.cost(), currency, row.effectiveFrom(), source,
                basisJson(row.basis()), setBy, writeId, importBatchIdOrNull
            });
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(INSERT, batch, batch.size(), PriceBookJdbc::bind);
            caches.evictAfterCommit(tenantId);
        }
        return new WriteResult(writeId, batch.size(), List.copyOf(skipped));
    }

    @Override
    @Transactional
    public UndoResult undo(UUID writeId) {
        UUID tenantId = Sql.tenant();
        int deleted = jdbc.update(UNDO, tenantId, writeId);
        Integer kept = jdbc.queryForObject(
                "SELECT count(*) FROM product_prices WHERE tenant_id = ? AND write_id = ?", Integer.class,
                tenantId, writeId);
        if (deleted > 0) {
            caches.evictAfterCommit(tenantId);
        }
        return new UndoResult(deleted, kept == null ? 0 : kept);
    }

    private String currency(UUID tenantId) {
        List<String> found = jdbc.queryForList(
                "SELECT trading_currency FROM tenant_settings WHERE tenant_id = ?", String.class, tenantId);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        List<String> fallback = jdbc.queryForList(
                "SELECT trading_currency FROM tenants WHERE id = ?", String.class, tenantId);
        return fallback.isEmpty() ? "USD" : fallback.get(0);
    }

    private Map<String, UUID> index(String sql, UUID tenantId) {
        Map<String, UUID> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            out.putIfAbsent(key(rs.getString("k")), rs.getObject("id", UUID.class));
        }, tenantId);
        return out;
    }

    private String basisJson(Map<String, Object> basis) {
        if (basis == null || basis.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(basis);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("The price basis could not be serialised", ex);
        }
    }

    private static String key(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }

    private static void bind(PreparedStatement ps, Object[] row) throws SQLException {
        for (int i = 0; i < row.length; i++) {
            Object value = row[i];
            if (value == null) {
                ps.setObject(i + 1, null, Types.OTHER);
            } else if (value instanceof java.time.LocalDate date) {
                ps.setObject(i + 1, java.sql.Date.valueOf(date));
            } else {
                ps.setObject(i + 1, value);
            }
        }
    }
}
