package com.aatlas.history.internal;

import com.aatlas.history.Inventory;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code inventory_positions}: one row per (product, store), the latest count. */
@Repository
class InventoryJdbc implements Inventory {

    private final NamedParameterJdbcTemplate jdbc;

    InventoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public boolean hasInventory() {
        Boolean any = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM inventory_positions ip WHERE ip.tenant_id = :t)",
                Sql.params(), Boolean.class);
        return Boolean.TRUE.equals(any);
    }

    @Override
    public Optional<OnHand> onHand(UUID productId, UUID storeIdOrNull) {
        MapSqlParameterSource params = Sql.params().addValue("p", productId);
        String scope = storeIdOrNull != null ? "ip.store_id = :s" : "ip.store_id IS NULL";
        if (storeIdOrNull != null) {
            params.addValue("s", storeIdOrNull);
        }
        return Optional.ofNullable(jdbc.query(
                "SELECT ip.on_hand, ip.as_of FROM inventory_positions ip WHERE ip.tenant_id = :t AND ip.product_id = :p AND "
                        + scope + " ORDER BY ip.as_of DESC LIMIT 1", params,
                rs -> rs.next() ? new OnHand(Sql.moneyOrZero(rs, "on_hand"), Sql.date(rs, "as_of")) : null));
    }

    @Override
    public Map<PairKey, OnHand> all() {
        Map<PairKey, OnHand> out = new HashMap<>();
        jdbc.query("SELECT ip.product_id, ip.store_id, ip.on_hand, ip.as_of FROM inventory_positions ip"
                + " WHERE ip.tenant_id = :t", Sql.params(), rs -> {
            out.put(new PairKey(Sql.uuid(rs, "product_id"), Sql.uuid(rs, "store_id")),
                    new OnHand(Sql.moneyOrZero(rs, "on_hand"), Sql.date(rs, "as_of")));
        });
        return out;
    }

    @Override
    public Optional<LocalDate> latestAsOf() {
        return Optional.ofNullable(jdbc.query(
                "SELECT max(ip.as_of) AS as_of FROM inventory_positions ip WHERE ip.tenant_id = :t", Sql.params(),
                rs -> rs.next() ? Sql.date(rs, "as_of") : null));
    }

    @Override
    public InventoryCoverage coverage() {
        return jdbc.query("""
                SELECT count(DISTINCT ip.product_id) AS items, count(DISTINCT ip.store_id) AS stores, max(ip.as_of) AS as_of
                  FROM inventory_positions ip WHERE ip.tenant_id = :t
                """, Sql.params(), rs -> {
            rs.next();
            return new InventoryCoverage(rs.getInt("items"), rs.getInt("stores"), Sql.date(rs, "as_of"));
        });
    }
}
