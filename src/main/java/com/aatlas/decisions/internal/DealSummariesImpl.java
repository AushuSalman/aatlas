package com.aatlas.decisions.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.decisions.DealSummaries;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DealSummariesImpl implements DealSummaries {

    private final JdbcTemplate jdbc;

    DealSummariesImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Adoption adoption(String side, LocalDate from, LocalDate to, String storeCodeOrNull) {
        UUID tenantId = TenantContext.requireTenantId();
        String sql = """
                select count(*) as total, count(*) filter (where followed) as followed
                  from deal
                 where tenant_id = ? and side = ? and deal_date between ? and ?
                """;
        if (storeCodeOrNull == null) {
            return jdbc.queryForObject(sql, (rs, i) -> new Adoption(rs.getInt("total"), rs.getInt("followed")),
                    tenantId, side, from, to);
        }
        return jdbc.queryForObject(sql + " and destination_id = ?",
                (rs, i) -> new Adoption(rs.getInt("total"), rs.getInt("followed")),
                tenantId, side, from, to, storeCodeOrNull);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Acceptance> acceptance(String side, String itemNumberOrNull, String storeCodeOrNull, LocalDate since,
            int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        StringBuilder sql = new StringBuilder("""
                select deal_date, item_number, destination_id, suggested_price, actual_price, followed
                  from deal
                 where tenant_id = ? and side = ? and deal_date >= ?
                   and suggested_price > 0 and actual_price > 0
                """);
        List<Object> args = new ArrayList<>(List.of(tenantId, sideOf(side), since));
        if (itemNumberOrNull != null && !itemNumberOrNull.isBlank()) {
            sql.append(" and item_number = ?");
            args.add(itemNumberOrNull.strip());
            if (storeCodeOrNull != null && !storeCodeOrNull.isBlank()) {
                sql.append(" and destination_id = ?");
                args.add(storeCodeOrNull.strip());
            }
        }
        sql.append(" order by deal_date desc, created_at desc limit ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        return jdbc.query(sql.toString(), (rs, i) -> new Acceptance(rs.getObject("deal_date", LocalDate.class),
                rs.getString("item_number"), rs.getString("destination_id"), rs.getBigDecimal("suggested_price"),
                rs.getBigDecimal("actual_price"), rs.getBoolean("followed")), args.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public long priorApplied(String side, String itemNumber, String storeCodeOrNull) {
        UUID tenantId = TenantContext.requireTenantId();
        if (itemNumber == null || itemNumber.isBlank()) {
            return 0;
        }
        if (storeCodeOrNull == null || storeCodeOrNull.isBlank()) {
            Long n = jdbc.queryForObject(
                    "select count(*) from deal where tenant_id = ? and side = ? and item_number = ?",
                    Long.class, tenantId, sideOf(side), itemNumber.strip());
            return n == null ? 0 : n;
        }
        Long n = jdbc.queryForObject(
                "select count(*) from deal where tenant_id = ? and side = ? and item_number = ? and destination_id = ?",
                Long.class, tenantId, sideOf(side), itemNumber.strip(), storeCodeOrNull.strip());
        return n == null ? 0 : n;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> strategyPicks(String side, LocalDate since) {
        UUID tenantId = TenantContext.requireTenantId();
        Map<String, Long> out = new LinkedHashMap<>();
        // bulk_decision belongs to the bulk module, which depends on this one; read by SQL,
        // the way every cross-module reference figure is, rather than through a cycle.
        jdbc.query("""
                select strategy_key, count(*) as n
                  from bulk_decision
                 where tenant_id = ? and kind = ? and created_at >= ?
                 group by strategy_key
                """, rs -> {
            out.put(rs.getString("strategy_key"), rs.getLong("n"));
        }, tenantId, sideOf(side), since.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
        return out;
    }

    /** {@code sell} or {@code buy}; anything else is a caller bug, not a query. */
    private static String sideOf(String side) {
        if (SELL.equals(side) || BUY.equals(side)) {
            return side;
        }
        throw new IllegalArgumentException("Unknown deal side '" + side + "'");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LocalDate> latestDeal() {
        UUID tenantId = TenantContext.requireTenantId();
        LocalDate latest = jdbc.query("select max(deal_date) from deal where tenant_id = ?",
                rs -> rs.next() ? rs.getObject(1, LocalDate.class) : null, tenantId);
        return Optional.ofNullable(latest);
    }
}
