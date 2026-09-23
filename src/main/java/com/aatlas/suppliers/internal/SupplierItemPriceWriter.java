package com.aatlas.suppliers.internal;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * One row of a supplier's price list, written by hand.
 *
 * <p>{@code supplier_products} is the table the buy engine reads its quotes from, and until
 * now only the importers wrote to it. A price typed in carries {@code ex_works_source =
 * 'manual'}, which the purchases importer respects the same way it respects an imported one:
 * a later observed price updates it, and the row says where the figure came from.
 *
 * <p>JDBC rather than an entity, like {@link SupplierProductLinkSeeder}: the row is a
 * relation between two other modules' tables and has no behaviour of its own.
 */
@Repository
class SupplierItemPriceWriter {

    private static final String UPSERT = """
            insert into supplier_products (
                tenant_id, supplier_id, product_id, ex_works, moq, lead_time_days, ex_works_source, ex_works_as_of)
            values (?, ?, ?, ?, ?, ?, 'manual', ?)
            on conflict (tenant_id, supplier_id, product_id) do update set
                ex_works        = excluded.ex_works,
                moq             = coalesce(excluded.moq, supplier_products.moq),
                lead_time_days  = coalesce(excluded.lead_time_days, supplier_products.lead_time_days),
                ex_works_source = excluded.ex_works_source,
                ex_works_as_of  = excluded.ex_works_as_of,
                updated_at      = now()
            """;

    private final JdbcTemplate jdbc;

    SupplierItemPriceWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void write(UUID tenantId, UUID supplierId, UUID productId, BigDecimal exWorks, Integer moq, Integer leadTimeDays,
            LocalDate asOf) {
        jdbc.update(UPSERT, tenantId, supplierId, productId, exWorks, moq, leadTimeDays, java.sql.Date.valueOf(asOf));
    }
}
