package com.aatlas.buy.internal;

import com.aatlas.history.Suppliers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tenant's supplier panel, as {@code buy} needs it: real rows from {@link Suppliers} (the
 * {@code history} module's read layer over {@code suppliers}/{@code supplier_products}), plus
 * the two derived-rating tables ({@code supplier_ratings}, {@code supplier_reviews}) that
 * {@code history} does not expose because they belong to the {@code suppliers} module's own
 * read side, which {@code buy}'s allowed dependencies do not include.
 *
 * <p>Every figure here is either a tenant row ({@code Suppliers}) or a direct read of a table
 * {@code suppliers} itself seeded once, at tenant provisioning, from {@code
 * seed/suppliers.json} - real per-supplier profile facts (their on-time record, lead time,
 * defect rate, star rating), not a hash of a code computed on every request. Never {@code
 * Seeded} here.
 */
@Component
public class SupplierGateway {

    private final Suppliers suppliers;
    private final JdbcTemplate jdbc;

    SupplierGateway(Suppliers suppliers, JdbcTemplate jdbc) {
        this.suppliers = suppliers;
        this.jdbc = jdbc;
    }

    /** {@code id} is the wire id ({@code supplier_key}, e.g. {@code sup-2}), never the uuid. */
    public record SupplierRow(
            String id, String name, String country, String category, Integer leadTimeDays, BigDecimal otifPct,
            BigDecimal priceIndex, BigDecimal defectPct, Boolean holdsStock, Integer yearsTrading, LocalDate since,
            boolean custom) {

        static SupplierRow from(Suppliers.SupplierRef r) {
            return new SupplierRow(r.supplierKey(), r.name(), r.country(), r.category(), r.leadTimeDays(),
                    r.otifPct(), r.priceIndex(), r.defectPct(), r.holdsStock(), r.yearsTrading(), r.since(),
                    r.custom());
        }
    }

    /** One (supplier, item) link: the ex-works quote on file, or none. */
    public record Quote(SupplierRow supplier, BigDecimal exWorks, String exWorksSource, LocalDate exWorksAsOf,
            Integer moq, Integer leadTimeDays) {

        static Quote from(Suppliers.SupplierLink l) {
            return new Quote(SupplierRow.from(l.supplier()), l.exWorks(), l.exWorksSource(), l.exWorksAsOf(),
                    l.moq(), l.leadTimeDays());
        }
    }

    /** The whole panel: every supplier this tenant has, whether or not they carry any given item. */
    public List<SupplierRow> panel() {
        return suppliers.panel().stream().map(SupplierRow::from).toList();
    }

    public Optional<SupplierRow> supplier(String supplierKey) {
        return suppliers.supplier(supplierKey).map(SupplierRow::from);
    }

    /**
     * The suppliers actually linked to one item, real ex-works quotes only - never falls back
     * to the whole panel with an invented quote. Empty when nobody is linked yet; the caller
     * (see {@code BuyRecommendationEngine}) decides whether to show the rest of the panel with
     * a null quote in that case.
     */
    public List<Quote> quotesFor(UUID productId) {
        return suppliers.panelFor(productId).stream().map(Quote::from).toList();
    }

    public Optional<Suppliers.Terms> terms(String supplierKey) {
        return suppliers.terms(supplierKey);
    }

    /** The derived star rating stored at seed time, breakdown only over the dims that were provided. */
    public record Rating(BigDecimal rating, Integer reviewCount, BigDecimal quality, BigDecimal delivery,
            BigDecimal communication, BigDecimal pricing, String label) {
    }

    public Optional<Rating> rating(String supplierKey) {
        List<Rating> rows = jdbc.query("""
                SELECT r.rating, r.review_count, r.quality, r.delivery, r.communication, r.pricing, r.label
                  FROM supplier_ratings r
                  JOIN suppliers s ON s.id = r.supplier_id
                 WHERE r.tenant_id = ? AND s.supplier_key = ?
                """,
                (rs, i) -> new Rating(rs.getBigDecimal("rating"), (Integer) rs.getObject("review_count"),
                        rs.getBigDecimal("quality"), rs.getBigDecimal("delivery"), rs.getBigDecimal("communication"),
                        rs.getBigDecimal("pricing"), rs.getString("label")),
                com.aatlas.common.tenant.TenantContext.requireTenantId(), supplierKey);
        return rows.stream().findFirst();
    }

    /** Real count of {@code supplier_reviews} rows - zero, never a seeded range, when nobody has reviewed them. */
    public int reviewCount(String supplierKey) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM supplier_reviews sr
                  JOIN suppliers s ON s.id = sr.supplier_id
                 WHERE sr.tenant_id = ? AND s.supplier_key = ?
                """, Integer.class, com.aatlas.common.tenant.TenantContext.requireTenantId(), supplierKey);
        return count == null ? 0 : count;
    }
}
