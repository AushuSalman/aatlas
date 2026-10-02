package com.aatlas.supplymodel.internal;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The tenant's received purchase orders as {@link OrderGrid} reads them, and the item lookups
 * a forecast needs. Reads {@code purchase_order} directly: an order counts once it has a
 * received date and both a promised and an actual lead time, which is what the slip and the
 * late flag are made of.
 */
@Repository
class OrderRows {

    private final JdbcTemplate jdbc;

    OrderRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    List<OrderGrid.Order> received(UUID tenantId, LocalDate from) {
        return jdbc.query("""
                select o.order_date, coalesce(sp.id::text, o.supplier_id) as supplier, o.supplier_name, o.country,
                       o.item_number, o.category, coalesce(s.store_code, o.branch_name) as store, o.qty,
                       o.promised_days, o.actual_days, o.on_time
                  from purchase_order o
                  left join stores s on s.id = o.store_id
                  left join suppliers sp on sp.tenant_id = o.tenant_id
                       and (sp.id::text = o.supplier_id or sp.vendor_code = o.supplier_id or sp.name = o.supplier_name)
                 where o.tenant_id = ? and o.received_date is not null and o.order_date >= ?
                   and o.promised_days is not null and o.actual_days is not null and o.supplier_id is not null
                 order by o.order_date, o.seq
                """, (rs, i) -> new OrderGrid.Order(rs.getObject(1, LocalDate.class), rs.getString(2),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        dbl(rs.getBigDecimal(8)), rs.getInt(9), rs.getInt(10), !rs.getBoolean(11)),
                tenantId, Date.valueOf(from));
    }

    /** The item's category on the product file, for a forecast's one-hot. */
    @Transactional(readOnly = true)
    Optional<String> categoryOf(UUID tenantId, String itemNumber) {
        return jdbc.query("select category from products where tenant_id = ? and item_number = ? limit 1",
                rs -> rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty(), tenantId, itemNumber);
    }

    /** Received orders written after the last training run: the model has not seen them yet. */
    @Transactional(readOnly = true)
    int receivedSince(UUID tenantId, Instant trainedAt) {
        if (trainedAt == null) {
            return 0;
        }
        Integer n = jdbc.queryForObject("""
                select count(*) from purchase_order
                 where tenant_id = ? and received_date is not null and updated_at > ?
                """, Integer.class, tenantId, Timestamp.from(trainedAt));
        return n == null ? 0 : n;
    }

    private static double dbl(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }
}
