package com.aatlas.ingest.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes a committed import back out.
 *
 * <p>Per kind, the rows the batch wrote are deleted and the derived figures restated
 * (product-store windows, baselines, observed supplier prices). Then, for every kind, the
 * products, branches, customers and suppliers this batch created are deleted where nothing
 * else references them - a product that later received a price or a competitor observation
 * survives its creating batch's rollback, which the data guide says. Category edits from a
 * products import stay: there is no before-image.
 *
 * <p>Runs inside the caller's transaction ({@link Propagation#MANDATORY}) so the status change
 * and the deletions commit together.
 */
@Component
class ImportRollback {

    private static final Logger log = LoggerFactory.getLogger(ImportRollback.class);

    private final JdbcTemplate jdbc;
    private final PurchaseOrderLoader purchases;

    ImportRollback(JdbcTemplate jdbc, PurchaseOrderLoader purchases) {
        this.jdbc = jdbc;
        this.purchases = purchases;
    }

    /**
     * Removes everything the batch loaded and created.
     *
     * @return the names of suppliers this batch created that had to be kept because something
     *     else (an RFQ, an award) references them
     */
    @Transactional(propagation = Propagation.MANDATORY)
    List<String> rollback(ImportBatchEntity batch) {
        UUID tenantId = batch.getTenantId();
        UUID batchId = batch.getId();
        switch (batch.kind()) {
            case SALES -> sales(tenantId, batchId);
            case PURCHASES -> purchases(tenantId, batchId);
            case PRODUCTS -> products(tenantId, batchId);
            case COMPETITOR_PRICES -> competitorPrices(tenantId, batchId);
        }
        return sweep(tenantId, batchId);
    }

    private void sales(UUID tenantId, UUID batchId) {
        jdbc.update("delete from sales_transactions where tenant_id = ? and import_batch_id = ?", tenantId, batchId);
        jdbc.update("""
                update product_stores ps set first_sale_at = a.f, last_sale_at = a.l, updated_at = now()
                  from (select product_id, store_id, min(txn_date) f, max(txn_date) l
                          from sales_transactions where tenant_id = ? group by 1, 2) a
                 where ps.tenant_id = ? and ps.product_id = a.product_id and ps.store_id = a.store_id
                """, tenantId, tenantId);
        jdbc.update("""
                delete from product_stores ps
                 where ps.tenant_id = ? and ps.import_batch_id = ?
                   and not exists (select 1 from sales_transactions st
                                    where st.tenant_id = ps.tenant_id and st.product_id = ps.product_id
                                      and st.store_id = ps.store_id)
                """, tenantId, batchId);
        jdbc.update("""
                update products p
                   set has_sales = exists (select 1 from sales_transactions st
                                            where st.tenant_id = p.tenant_id and st.product_id = p.id)
                 where p.tenant_id = ? and p.source = 'import'
                """, tenantId);
    }

    private void purchases(UUID tenantId, UUID batchId) {
        jdbc.update("delete from purchase_order where tenant_id = ? and import_batch_id = ?", tenantId, batchId);
        purchases.refreshObservedSupplierProducts(tenantId);
        purchases.recomputeBaselines(tenantId);
    }

    private void products(UUID tenantId, UUID batchId) {
        jdbc.update("delete from product_prices where tenant_id = ? and import_batch_id = ?", tenantId, batchId);
        jdbc.update("delete from inventory_positions where tenant_id = ? and import_batch_id = ?", tenantId, batchId);
        jdbc.update("""
                update supplier_products
                   set ex_works = null, ex_works_as_of = null, lead_time_days = null,
                       ex_works_source = null, import_batch_id = null, updated_at = now()
                 where tenant_id = ? and import_batch_id = ? and ex_works_source = 'import'
                """, tenantId, batchId);
    }

    private void competitorPrices(UUID tenantId, UUID batchId) {
        jdbc.update("delete from competitor_prices where tenant_id = ? and import_batch_id = ?", tenantId, batchId);
    }

    /** Every kind, last: the catalogue rows this batch created that nothing references any more. */
    private List<String> sweep(UUID tenantId, UUID batchId) {
        int products = jdbc.update("""
                delete from products p
                 where p.tenant_id = ? and p.import_batch_id = ?
                   and not exists (select 1 from sales_transactions x where x.tenant_id = p.tenant_id and x.product_id = p.id)
                   and not exists (select 1 from purchase_order x where x.tenant_id = p.tenant_id and x.product_id = p.id)
                   and not exists (select 1 from product_prices x where x.product_id = p.id)
                   and not exists (select 1 from inventory_positions x where x.product_id = p.id)
                   and not exists (select 1 from competitor_prices x where x.product_id = p.id)
                   and not exists (select 1 from supplier_products sp where sp.product_id = p.id and sp.ex_works is not null)
                """, tenantId, batchId);
        int stores = jdbc.update("""
                delete from stores s
                 where s.tenant_id = ? and s.import_batch_id = ?
                   and not exists (select 1 from sales_transactions x where x.tenant_id = s.tenant_id and x.store_id = s.id)
                   and not exists (select 1 from product_prices x where x.store_id = s.id)
                   and not exists (select 1 from inventory_positions x where x.store_id = s.id)
                   and not exists (select 1 from competitor_prices x where x.store_id = s.id)
                   and not exists (select 1 from purchase_order x
                                    where x.tenant_id = s.tenant_id and (x.store_id = s.id or x.branch_id = s.store_code))
                """, tenantId, batchId);
        int customers = jdbc.update("""
                delete from customers c
                 where c.tenant_id = ? and c.import_batch_id = ?
                   and not exists (select 1 from sales_transactions x where x.tenant_id = c.tenant_id and x.customer_id = c.id)
                """, tenantId, batchId);

        List<String> kept = new ArrayList<>();
        List<Map<String, Object>> candidates = jdbc.queryForList("""
                select s.id, s.name from suppliers s
                 where s.tenant_id = ? and s.import_batch_id = ?
                   and not exists (select 1 from purchase_order po
                                    where po.tenant_id = s.tenant_id and po.supplier_id = s.supplier_key)
                """, tenantId, batchId);
        int suppliers = 0;
        for (Map<String, Object> row : candidates) {
            UUID id = (UUID) row.get("id");
            // A savepoint per supplier: a foreign key from an RFQ or an award aborts only this
            // delete, and the transaction carries on with the supplier kept and reported.
            jdbc.execute("savepoint sweep_supplier");
            try {
                suppliers += jdbc.update("delete from suppliers where tenant_id = ? and id = ?", tenantId, id);
                jdbc.execute("release savepoint sweep_supplier");
            } catch (DataIntegrityViolationException ex) {
                jdbc.execute("rollback to savepoint sweep_supplier");
                kept.add((String) row.get("name"));
            }
        }
        log.info("Rollback sweep for batch {}: {} products, {} stores, {} customers, {} suppliers deleted, {} kept",
                batchId, products, stores, customers, suppliers, kept.size());
        return kept;
    }
}
