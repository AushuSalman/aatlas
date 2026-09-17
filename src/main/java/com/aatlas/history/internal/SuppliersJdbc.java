package com.aatlas.history.internal;

import com.aatlas.history.Suppliers;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The supplier panel by JDBC: every figure nullable, never defaulted. */
@Repository
class SuppliersJdbc implements Suppliers {

    private static final String SUPPLIER_COLUMNS = """
            SELECT s.id, s.supplier_key, s.name, s.country, s.vendor_code, s.category, s.price_index, s.lead_time_days,
                   s.otif_pct, s.defect_pct, s.holds_stock, s.years_trading, s.since, s.is_custom, s.source
              FROM suppliers s
            """;

    private final JdbcTemplate jdbc;

    SuppliersJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static SupplierRef readSupplier(ResultSet rs) throws SQLException {
        return new SupplierRef(Sql.uuid(rs, "id"), rs.getString("supplier_key"), rs.getString("name"),
                rs.getString("country"), rs.getString("vendor_code"), rs.getString("category"),
                rs.getBigDecimal("price_index"), Sql.integer(rs, "lead_time_days"), rs.getBigDecimal("otif_pct"),
                rs.getBigDecimal("defect_pct"), Sql.bool(rs, "holds_stock"), Sql.integer(rs, "years_trading"),
                Sql.date(rs, "since"), rs.getBoolean("is_custom"), rs.getString("source"));
    }

    @Override
    public List<SupplierRef> panel() {
        return jdbc.query(SUPPLIER_COLUMNS + " WHERE s.tenant_id = ? ORDER BY s.name",
                (rs, i) -> readSupplier(rs), Sql.tenant());
    }

    @Override
    public Optional<SupplierRef> supplier(String supplierKey) {
        if (supplierKey == null || supplierKey.isBlank()) {
            return Optional.empty();
        }
        List<SupplierRef> rows = jdbc.query(SUPPLIER_COLUMNS + " WHERE s.tenant_id = ? AND s.supplier_key = ?",
                (rs, i) -> readSupplier(rs), Sql.tenant(), supplierKey.strip());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<SupplierLink> panelFor(UUID productId) {
        return jdbc.query(SUPPLIER_COLUMNS.replace("FROM suppliers s", "")
                + ", sp.ex_works, sp.ex_works_source, sp.ex_works_as_of, sp.moq, sp.lead_time_days AS link_lead"
                + " FROM supplier_products sp JOIN suppliers s ON s.id = sp.supplier_id"
                + " WHERE sp.tenant_id = ? AND sp.product_id = ? ORDER BY s.name",
                (rs, i) -> new SupplierLink(readSupplier(rs), Sql.money(rs, "ex_works"), rs.getString("ex_works_source"),
                        Sql.date(rs, "ex_works_as_of"), Sql.integer(rs, "moq"), Sql.integer(rs, "link_lead")),
                Sql.tenant(), productId);
    }

    @Override
    public Optional<Terms> terms(String supplierKey) {
        if (supplierKey == null || supplierKey.isBlank()) {
            return Optional.empty();
        }
        List<Terms> rows = jdbc.query("""
                SELECT t.credit_days, t.terms_label, t.early_pay_discount_pct, t.early_pay_days, t.late_penalty_pct_per_week,
                       t.late_penalty_cap_pct, t.warranty_months, t.quote_validity_days, t.incoterm, t.invoice_accuracy_pct,
                       t.capacity_units_month, t.moq, t.order_multiple, t.quality_ppm, t.response_hours, t.certifications
                  FROM supplier_terms t JOIN suppliers s ON s.id = t.supplier_id
                 WHERE t.tenant_id = ? AND s.supplier_key = ?
                """, (rs, i) -> new Terms(Sql.integer(rs, "credit_days"), rs.getString("terms_label"),
                rs.getBigDecimal("early_pay_discount_pct"), Sql.integer(rs, "early_pay_days"),
                rs.getBigDecimal("late_penalty_pct_per_week"), rs.getBigDecimal("late_penalty_cap_pct"),
                Sql.integer(rs, "warranty_months"), Sql.integer(rs, "quote_validity_days"), rs.getString("incoterm"),
                rs.getBigDecimal("invoice_accuracy_pct"), Sql.integer(rs, "capacity_units_month"),
                Sql.integer(rs, "moq"), Sql.integer(rs, "order_multiple"), Sql.integer(rs, "quality_ppm"),
                Sql.integer(rs, "response_hours"), strings(rs.getArray("certifications"))),
                Sql.tenant(), supplierKey.strip());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        Object raw = array.getArray();
        if (raw instanceof String[] values) {
            return Arrays.asList(values);
        }
        return List.of();
    }

    @Override
    public SupplierCoverage coverage() {
        UUID tenant = Sql.tenant();
        return jdbc.query("""
                SELECT (SELECT count(*) FROM suppliers s WHERE s.tenant_id = ?) AS total,
                       (SELECT count(*) FROM supplier_terms t WHERE t.tenant_id = ?) AS with_terms,
                       (SELECT count(DISTINCT po.supplier_id) FROM purchase_order po WHERE po.tenant_id = ?) AS with_purchases
                """, rs -> {
            rs.next();
            return new SupplierCoverage(rs.getInt("total"), rs.getInt("with_terms"), rs.getInt("with_purchases"));
        }, tenant, tenant, tenant);
    }
}
