package com.aatlas.ingest.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.history.HistoryCaches;
import com.aatlas.ingest.SalesLedger;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One hand-recorded sale as one {@code sales_transactions} line, written the way
 * {@link SalesTransactionLoader} writes an imported one: the month partition made sure of,
 * the product and branch resolved by code, the {@code product_stores} link widened, and the
 * tenant's caches dropped after commit so the next read of the Overview or Insights sees it.
 */
@Service
class SalesLedgerJdbc implements SalesLedger {

    private static final Logger log = LoggerFactory.getLogger(SalesLedgerJdbc.class);

    private static final String INSERT_SQL = """
            insert into sales_transactions (
                tenant_id, txn_date, product_id, store_id, customer_id,
                item_number, branch_code, customer_code, description,
                qty, unit_price, unit_cost, source, import_batch_id, source_line,
                invoice_no, currency, uom)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'manual', null, null, ?, ?, ?)
            returning id
            """;

    private static final String LINK_SQL = """
            insert into product_stores (tenant_id, product_id, store_id, sells, first_sale_at, last_sale_at)
            values (?, ?, ?, true, ?, ?)
            on conflict (tenant_id, product_id, store_id) do update
               set sells         = true,
                   first_sale_at = least(product_stores.first_sale_at, excluded.first_sale_at),
                   last_sale_at  = greatest(product_stores.last_sale_at, excluded.last_sale_at),
                   updated_at    = now()
            """;

    private final JdbcTemplate jdbc;
    private final HistoryCaches caches;

    SalesLedgerJdbc(JdbcTemplate jdbc, HistoryCaches caches) {
        this.jdbc = jdbc;
        this.caches = caches;
    }

    @Override
    @Transactional
    public Optional<UUID> record(RecordedSale sale) {
        UUID tenantId = TenantContext.requireTenantId();
        if (sale.itemNumber() == null || sale.itemNumber().isBlank() || sale.qty() == null
                || sale.qty().signum() <= 0 || sale.unitPrice() == null || sale.unitPrice().signum() < 0
                || sale.date() == null) {
            return Optional.empty();
        }
        List<Row> product = jdbc.query("select id, description, unit as uom from products where tenant_id = ? and item_number = ?",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("description"), rs.getString("uom")),
                tenantId, sale.itemNumber().strip());
        if (product.isEmpty()) {
            log.info("Sale of {} not booked into the sales history: not in the catalogue", sale.itemNumber());
            return Optional.empty();
        }
        UUID productId = product.get(0).id();
        UUID storeId = null;
        if (sale.storeCode() != null && !sale.storeCode().isBlank()) {
            List<UUID> stores = jdbc.query("select id from stores where tenant_id = ? and store_code = ?",
                    (rs, i) -> rs.getObject("id", UUID.class), tenantId, sale.storeCode().strip());
            storeId = stores.isEmpty() ? null : stores.get(0);
        }
        UUID customerId = null;
        String customerCode = sale.customerCode() == null || sale.customerCode().isBlank() ? null : sale.customerCode().strip();
        if (customerCode != null) {
            List<UUID> customers = jdbc.query("select id from customers where tenant_id = ? and code = ?",
                    (rs, i) -> rs.getObject("id", UUID.class), tenantId, customerCode);
            customerId = customers.isEmpty() ? null : customers.get(0);
        }
        String currency = jdbc.query("select trading_currency from tenant_settings where tenant_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, tenantId);

        jdbc.queryForObject("select app.ensure_month_partition('sales_transactions', ?)", String.class,
                java.sql.Date.valueOf(sale.date()));

        String description = sale.description() != null && !sale.description().isBlank() ? sale.description()
                : product.get(0).description();
        BigDecimal unitCost = sale.unitCost() == null || sale.unitCost().signum() < 0 ? null : sale.unitCost();
        UUID storeArg = storeId;
        UUID customerArg = customerId;
        UUID id = jdbc.query(INSERT_SQL, ps -> {
            ps.setObject(1, tenantId);
            ps.setObject(2, java.sql.Date.valueOf(sale.date()));
            ps.setObject(3, productId);
            if (storeArg == null) {
                ps.setNull(4, Types.OTHER);
            } else {
                ps.setObject(4, storeArg);
            }
            if (customerArg == null) {
                ps.setNull(5, Types.OTHER);
            } else {
                ps.setObject(5, customerArg);
            }
            ps.setString(6, sale.itemNumber().strip());
            ps.setString(7, sale.storeCode() == null || sale.storeCode().isBlank() ? null : sale.storeCode().strip());
            ps.setString(8, customerCode);
            ps.setString(9, description);
            ps.setBigDecimal(10, sale.qty());
            ps.setBigDecimal(11, sale.unitPrice());
            ps.setBigDecimal(12, unitCost);
            ps.setString(13, sale.reference());
            ps.setString(14, currency);
            ps.setString(15, product.get(0).uom());
        }, rs -> rs.next() ? rs.getObject(1, UUID.class) : null);

        if (storeId != null) {
            jdbc.update(LINK_SQL, tenantId, productId, storeId, java.sql.Date.valueOf(sale.date()),
                    java.sql.Date.valueOf(sale.date()));
        }
        caches.evictAfterCommit(tenantId);
        return Optional.ofNullable(id);
    }

    private record Row(UUID id, String description, String uom) {
    }
}
