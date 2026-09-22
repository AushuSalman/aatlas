package com.aatlas.ingest.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.ingest.internal.csv.ImportKind;
import com.aatlas.ingest.internal.csv.ProductRow;
import com.aatlas.ingest.internal.csv.ValidationContext;
import com.aatlas.suppliers.SupplierResolver;
import com.aatlas.suppliers.SupplierResolver.SupplierRef;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes a product master: products created or updated, list prices and costs into
 * {@code product_prices}, stock into {@code inventory_positions}, preferred suppliers into
 * {@code supplier_products}.
 *
 * <p>A products file is a set of independent facts per row, so each is written where it
 * exists and skipped where it does not. Category, subcategory, unit and commodity are the
 * one thing an import edits in place - there is no before-image, and the data guide says so.
 */
@Component
class ProductMasterLoader implements KindLoader<ProductRow> {

    private static final Logger log = LoggerFactory.getLogger(ProductMasterLoader.class);

    private final JdbcTemplate jdbc;
    private final SupplierResolver suppliers;

    ProductMasterLoader(JdbcTemplate jdbc, SupplierResolver suppliers) {
        this.jdbc = jdbc;
        this.suppliers = suppliers;
    }

    @Override
    public ImportKind kind() {
        return ImportKind.PRODUCTS;
    }

    @Override
    public Load<ProductRow> begin(UUID tenantId, UUID batchId, String source, ValidationContext ctx) {
        return new ProductLoad(tenantId, batchId, source, ctx);
    }

    final class ProductLoad implements Load<ProductRow> {

        private final UUID tenantId;
        private final UUID batchId;
        private final String rowSource;
        private final String currency;
        private final LocalDate today;
        private final UUID setBy;
        private final CatalogueIndex index;
        private final Map<String, SupplierRef> suppliersByName = new HashMap<>();
        private final Set<String> supplierKeys = new LinkedHashSet<>();
        private final List<String> suppliersCreatedNames = new ArrayList<>();

        private int loaded;
        private int productsUpdated;
        private int pricesWritten;
        private int inventoryRowsWritten;
        private int supplierLinksWritten;
        private int suppliersCreated;

        private ProductLoad(UUID tenantId, UUID batchId, String source, ValidationContext ctx) {
            this.tenantId = tenantId;
            this.batchId = batchId;
            this.rowSource = CatalogueIndex.rowSource(source);
            this.currency = ctx.tenantCurrency();
            this.today = ctx.today();
            this.setBy = TenantContext.currentUserId().orElse(null);
            this.index = new CatalogueIndex(jdbc, tenantId, batchId, source);
        }

        @Override
        public void add(ProductRow row) {
            loaded++;
            CatalogueIndex.ProductRef product = upsertProduct(row);
            CatalogueIndex.StoreRef branch = row.branch() == null || row.branch().isBlank() ? null
                    : index.store(row.branch());

            if (row.listPrice() != null || row.unitCost() != null) {
                jdbc.update("""
                        insert into product_prices (
                            tenant_id, product_id, store_id, list_price, cost, currency, effective_from,
                            source, basis, set_by, import_batch_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, null, ?, ?)
                        """, ps -> {
                    ps.setObject(1, tenantId);
                    ps.setObject(2, product.id());
                    ps.setObject(3, branch == null ? null : branch.id(), Types.OTHER);
                    ps.setBigDecimal(4, row.listPrice());
                    ps.setBigDecimal(5, row.unitCost());
                    ps.setString(6, currency);
                    ps.setObject(7, java.sql.Date.valueOf(today));
                    ps.setString(8, rowSource);
                    ps.setObject(9, setBy, Types.OTHER);
                    ps.setObject(10, batchId);
                });
                pricesWritten++;
            }

            if (row.onHand() != null) {
                CatalogueIndex.StoreRef stockAt = branch == null ? index.main() : branch;
                jdbc.update("""
                        insert into inventory_positions (tenant_id, product_id, store_id, on_hand, as_of, source, import_batch_id)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (tenant_id, product_id, store_id) do update
                           set on_hand = excluded.on_hand, as_of = excluded.as_of, source = excluded.source,
                               import_batch_id = excluded.import_batch_id, updated_at = now()
                        """, tenantId, product.id(), stockAt.id(), row.onHand(), java.sql.Date.valueOf(today),
                        rowSource, batchId);
                inventoryRowsWritten++;
            }

            if (row.supplier() != null && !row.supplier().isBlank()) {
                SupplierRef supplier = resolveSupplier(row.supplier());
                supplierKeys.add(supplier.supplierKey());
                jdbc.update("""
                        insert into supplier_products (
                            tenant_id, supplier_id, product_id, ex_works, lead_time_days, ex_works_source, ex_works_as_of, import_batch_id)
                        values (?, ?, ?, ?, ?, case when ? then 'import' end, case when ? then ?::date end, ?)
                        on conflict (tenant_id, supplier_id, product_id) do update set
                           ex_works        = coalesce(excluded.ex_works, supplier_products.ex_works),
                           lead_time_days  = coalesce(excluded.lead_time_days, supplier_products.lead_time_days),
                           ex_works_source = case when excluded.ex_works is null then supplier_products.ex_works_source else 'import' end,
                           ex_works_as_of  = case when excluded.ex_works is null then supplier_products.ex_works_as_of else excluded.ex_works_as_of end,
                           import_batch_id = excluded.import_batch_id,
                           updated_at      = now()
                        """, ps -> {
                    boolean quoted = row.supplierCost() != null;
                    ps.setObject(1, tenantId);
                    ps.setObject(2, supplier.id());
                    ps.setObject(3, product.id());
                    ps.setBigDecimal(4, row.supplierCost());
                    ps.setObject(5, row.leadTime(), Types.INTEGER);
                    ps.setBoolean(6, quoted);
                    ps.setBoolean(7, quoted);
                    ps.setObject(8, java.sql.Date.valueOf(today));
                    ps.setObject(9, batchId);
                });
                supplierLinksWritten++;
            }
        }

        /** Creates the product with the file's values, or updates the ones the file gives. */
        private CatalogueIndex.ProductRef upsertProduct(ProductRow row) {
            String item = row.item().strip();
            CatalogueIndex.ProductRef existing = index.find(item);
            String description = blank(row.description()) ? null : row.description().strip();
            String category = blank(row.category()) ? null : row.category().strip();
            String subcategory = blank(row.subcategory()) ? null : row.subcategory().strip();
            String unit = blank(row.uom()) ? null : row.uom().strip();
            String commodity = row.commodity();

            if (existing == null) {
                String safeDescription = description == null ? item : description;
                UUID id = jdbc.queryForObject("""
                        insert into products (
                            tenant_id, item_number, description, short_name,
                            category, subcategory, commodity, unit, has_sales, source, import_batch_id)
                        values (?, ?, ?, ?, ?, ?, ?, ?, false, ?, ?)
                        on conflict (tenant_id, item_number) do update set item_number = excluded.item_number
                        returning id
                        """, UUID.class, tenantId, item, safeDescription, CatalogueIndex.shortName(safeDescription),
                        category == null ? "uncategorised" : category,
                        subcategory == null ? "uncategorised" : subcategory,
                        commodity == null ? "none" : commodity,
                        unit == null ? "each" : unit,
                        rowSource, batchId);
                CatalogueIndex.ProductRef created = new CatalogueIndex.ProductRef(
                        id, category == null ? "uncategorised" : category, safeDescription);
                index.remember(item, created, true);
                return created;
            }

            String shortName = description == null ? null : CatalogueIndex.shortName(description);
            int changed = jdbc.update("""
                    update products set
                        description = coalesce(?, description),
                        short_name  = coalesce(?, short_name),
                        category    = coalesce(?, category),
                        subcategory = coalesce(?, subcategory),
                        unit        = coalesce(?, unit),
                        commodity   = coalesce(?, commodity),
                        updated_at  = now()
                    where id = ? and tenant_id = ?
                      and row(description, short_name, category, subcategory, unit, commodity)
                          is distinct from row(coalesce(?, description), coalesce(?, short_name), coalesce(?, category),
                                               coalesce(?, subcategory), coalesce(?, unit), coalesce(?, commodity))
                    """, ps -> {
                ps.setString(1, description);
                ps.setString(2, shortName);
                ps.setString(3, category);
                ps.setString(4, subcategory);
                ps.setString(5, unit);
                ps.setString(6, commodity);
                ps.setObject(7, existing.id());
                ps.setObject(8, tenantId);
                ps.setString(9, description);
                ps.setString(10, shortName);
                ps.setString(11, category);
                ps.setString(12, subcategory);
                ps.setString(13, unit);
                ps.setString(14, commodity);
            });
            if (changed > 0) {
                productsUpdated++;
                CatalogueIndex.ProductRef updated = new CatalogueIndex.ProductRef(existing.id(),
                        category == null ? existing.category() : category,
                        description == null ? existing.description() : description);
                index.remember(item, updated, false);
                return updated;
            }
            return existing;
        }

        private SupplierRef resolveSupplier(String name) {
            String key = CatalogueIndex.key(name);
            SupplierRef cached = suppliersByName.get(key);
            if (cached != null) {
                return cached;
            }
            SupplierRef ref = suppliers.find(tenantId, name).orElseGet(() -> {
                SupplierRef created = suppliers.create(tenantId, name, null, "import", batchId);
                suppliersCreated++;
                suppliersCreatedNames.add(created.name());
                return created;
            });
            suppliersByName.put(key, ref);
            return ref;
        }

        @Override
        public LoadResult finish() {
            // A file with no Branch column creates no branch, and the catalogue gate refuses a
            // workspace with products but no store - so the main branch is created here, the
            // way a blank branch cell would have.
            if (loaded > 0 && !index.hasStores()) {
                index.main();
            }
            log.info("Products import {}: {} rows, {} created, {} updated, {} prices, {} stock rows, {} links",
                    batchId, loaded, index.productsCreated(), productsUpdated, pricesWritten, inventoryRowsWritten,
                    supplierLinksWritten);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("productsUpdated", productsUpdated);
            summary.put("pricesWritten", pricesWritten);
            summary.put("inventoryRowsWritten", inventoryRowsWritten);
            summary.put("suppliersCreated", suppliersCreated);
            summary.put("suppliersCreatedNames", List.copyOf(
                    suppliersCreatedNames.subList(0, Math.min(suppliersCreatedNames.size(), CatalogueIndex.MAX_REPORTED))));
            summary.put("supplierLinksWritten", supplierLinksWritten);
            return new LoadResult(loaded, index.productsCreated(), index.branchesCreated(),
                    index.branchesNeedingRegion(), supplierKeys.size(), summary);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
