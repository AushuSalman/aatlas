package com.aatlas.ingest.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.ingest.internal.ReadinessView.CatalogueInfo;
import com.aatlas.ingest.internal.ReadinessView.CompetitorInfo;
import com.aatlas.ingest.internal.ReadinessView.Feature;
import com.aatlas.ingest.internal.ReadinessView.InventoryInfo;
import com.aatlas.ingest.internal.ReadinessView.NextStep;
import com.aatlas.ingest.internal.ReadinessView.PricesInfo;
import com.aatlas.ingest.internal.ReadinessView.PurchasesInfo;
import com.aatlas.ingest.internal.ReadinessView.SalesInfo;
import com.aatlas.ingest.internal.ReadinessView.SampleBatch;
import com.aatlas.ingest.internal.ReadinessView.SampleLoading;
import com.aatlas.ingest.internal.ReadinessView.SourceInfo;
import com.aatlas.ingest.internal.ReadinessView.SuppliersInfo;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes {@link ReadinessView}: the counts, the feature rules and the next steps.
 *
 * <p>Tenant-scoped counts over the fact tables, cached under {@link CacheNames#READINESS}
 * and evicted by every writer through {@code HistoryCaches.evictAfterCommit}. The counts
 * here stand in for the history module's coverage methods until that module lands; every
 * query carries the tenant id and reads only that tenant's rows.
 */
@Service
class ReadinessService {

    /** A sample connected this recently with fewer than four batches is still being claimed. */
    private static final Duration CLAIM_GRACE = Duration.ofMinutes(5);

    private static final int SAMPLE_KINDS = 4;

    private static final String SALES_LABEL = "Sales history";
    private static final String PURCHASES_LABEL = "Purchase history";
    private static final String PRODUCTS_LABEL = "Product master & prices";
    private static final String COMPETITORS_LABEL = "Competitor prices";
    private static final String SUPPLIERS_LABEL = "Suppliers";

    private final JdbcTemplate jdbc;
    private final AatlasClock clock;

    ReadinessService(JdbcTemplate jdbc, AatlasClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Cacheable(cacheNames = CacheNames.READINESS,
            key = "T(com.aatlas.common.cache.CacheNames).key(#p0, 'readiness')")
    @Transactional(readOnly = true)
    public ReadinessView readiness(UUID tenantId) {
        LocalDate today = clock.today();
        Instant now = clock.now();

        SourceInfo source = source(tenantId);
        CatalogueInfo catalogue = catalogue(tenantId);
        SalesInfo sales = sales(tenantId);
        PurchasesInfo purchases = purchases(tenantId);
        PricesInfo prices = prices(tenantId, catalogue.products(), today);
        InventoryInfo inventory = inventory(tenantId);
        CompetitorInfo competitors = competitors(tenantId);
        SuppliersInfo suppliers = suppliers(tenantId);
        long deals = count("select count(*) from deal where tenant_id = ?", tenantId);
        // A pair is priceable when it sells or when the price list prices it at that branch -
        // a products-only import writes the latter and never the former.
        Long pairs = jdbc.queryForObject("""
                select count(*) from (
                    select product_id, store_id from product_stores where tenant_id = ? and sells
                    union
                    select product_id, store_id from product_prices
                     where tenant_id = ? and store_id is not null and list_price > 0 and effective_from <= ?
                ) x
                """, Long.class, tenantId, tenantId, Date.valueOf(today));
        long priceablePairs = pairs == null ? 0 : pairs;
        long performingSuppliers = count("""
                select count(*) from (select supplier_id from purchase_order
                                       where tenant_id = ? and received_date is not null
                                       group by supplier_id having count(*) >= 5) x
                """, tenantId);
        SampleLoading sampleLoading = sampleLoading(tenantId, source, now);

        List<Feature> features = features(catalogue, sales, purchases, prices, inventory, suppliers, deals,
                priceablePairs, performingSuppliers);
        List<NextStep> nextSteps = nextSteps(source, catalogue, sales, purchases, prices, inventory, competitors,
                suppliers, sampleLoading);

        return new ReadinessView(source, catalogue, sales, purchases, prices, inventory, competitors, suppliers,
                features, nextSteps, sampleLoading);
    }

    // ---- counts -------------------------------------------------------------

    private SourceInfo source(UUID tenantId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select kind, label from data_sources where tenant_id = ?
                 order by case when kind = 'sample' then 0 else 1 end, connected_at desc
                 limit 1
                """, tenantId);
        if (rows.isEmpty()) {
            return null;
        }
        return new SourceInfo((String) rows.getFirst().get("kind"), (String) rows.getFirst().get("label"));
    }

    private CatalogueInfo catalogue(UUID tenantId) {
        return jdbc.queryForObject("""
                select (select count(*) from products  where tenant_id = ?) products,
                       (select count(*) from stores    where tenant_id = ?) stores,
                       (select count(*) from customers where tenant_id = ?) customers,
                       (select count(*) from stores    where tenant_id = ? and region_key = 'unassigned') stores_unassigned,
                       (select count(*) from customers where tenant_id = ? and segment = 'unassigned') customers_unassigned
                """, (rs, i) -> new CatalogueInfo(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)),
                tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    private SalesInfo sales(UUID tenantId) {
        return jdbc.queryForObject("""
                select count(*), count(distinct date_trunc('month', txn_date)), min(txn_date), max(txn_date),
                       count(distinct product_id), count(distinct store_id), count(customer_id)
                  from sales_transactions where tenant_id = ?
                """, (rs, i) -> new SalesInfo(rs.getLong(1), rs.getInt(2), date(rs.getDate(3)), date(rs.getDate(4)),
                        rs.getInt(5), rs.getInt(6), rs.getLong(7)),
                tenantId);
    }

    private PurchasesInfo purchases(UUID tenantId) {
        return jdbc.queryForObject("""
                select count(*), count(distinct date_trunc('month', order_date)), min(order_date), max(order_date),
                       count(distinct supplier_id), count(distinct coalesce(product_id::text, item_number)),
                       count(received_date)
                  from purchase_order where tenant_id = ?
                """, (rs, i) -> new PurchasesInfo(rs.getLong(1), rs.getInt(2), date(rs.getDate(3)),
                        date(rs.getDate(4)), rs.getInt(5), rs.getInt(6), rs.getLong(7)),
                tenantId);
    }

    /**
     * The price and cost ladders resolved tenant-wide: an item has a price when the price
     * list (any branch, matching the ladder's tenant-wide fallback) or the trailing twelve
     * months of sales give one; a cost when the price list, the trailing 90 days of
     * purchases, costed sales or a supplier quote give one.
     */
    private PricesInfo prices(UUID tenantId, int products, LocalDate today) {
        Date salesFrom = Date.valueOf(today.minusMonths(12).plusDays(1));
        Date purchasesFrom = Date.valueOf(today.minusDays(89));
        Date asOf = Date.valueOf(today);
        int withPrice = jdbc.queryForObject("""
                select count(*) from (
                    select product_id from product_prices
                     where tenant_id = ? and list_price > 0 and effective_from <= ?
                    union
                    select product_id from sales_transactions
                     where tenant_id = ? and product_id is not null and txn_date >= ?
                ) x
                """, Integer.class, tenantId, asOf, tenantId, salesFrom);
        int withCost = jdbc.queryForObject("""
                select count(*) from (
                    select product_id from product_prices
                     where tenant_id = ? and cost > 0 and effective_from <= ?
                    union
                    select product_id from purchase_order
                     where tenant_id = ? and product_id is not null and order_date >= ?
                    union
                    select product_id from sales_transactions
                     where tenant_id = ? and product_id is not null and unit_cost is not null and txn_date >= ?
                    union
                    select product_id from supplier_products where tenant_id = ? and ex_works is not null
                ) x
                """, Integer.class, tenantId, asOf, tenantId, purchasesFrom, tenantId, salesFrom, tenantId);
        int quotes = (int) count(
                "select count(*) from supplier_products where tenant_id = ? and ex_works is not null", tenantId);
        return new PricesInfo(withPrice, withCost, Math.max(0, products - withPrice),
                Math.max(0, products - withCost), quotes);
    }

    private InventoryInfo inventory(UUID tenantId) {
        return jdbc.queryForObject("""
                select count(distinct product_id), count(distinct store_id), max(as_of)
                  from inventory_positions where tenant_id = ?
                """, (rs, i) -> new InventoryInfo(rs.getInt(1), rs.getInt(2), date(rs.getDate(3))), tenantId);
    }

    private CompetitorInfo competitors(UUID tenantId) {
        return jdbc.queryForObject("""
                select count(distinct product_id), count(*), count(distinct lower(competitor))
                  from competitor_prices where tenant_id = ?
                """, (rs, i) -> new CompetitorInfo(rs.getInt(1), rs.getLong(2), rs.getInt(3)), tenantId);
    }

    private SuppliersInfo suppliers(UUID tenantId) {
        return jdbc.queryForObject("""
                select (select count(*) from suppliers s where s.tenant_id = ?) total,
                       (select count(*) from suppliers s where s.tenant_id = ?
                          and exists (select 1 from supplier_terms t where t.supplier_id = s.id)) with_terms,
                       (select count(distinct supplier_id) from purchase_order where tenant_id = ?) with_purchases
                """, (rs, i) -> new SuppliersInfo(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                tenantId, tenantId, tenantId);
    }

    private SampleLoading sampleLoading(UUID tenantId, SourceInfo source, Instant now) {
        List<SampleBatch> batches = jdbc.query("""
                select kind, status, failure_reason from import_batches
                 where tenant_id = ? and source = 'sample' and status <> 'ROLLED_BACK'
                 order by created_at
                """, (rs, i) -> new SampleBatch(rs.getString(1), rs.getString(2), rs.getString(3)), tenantId);
        boolean committing = batches.stream().anyMatch(b -> "COMMITTING".equals(b.status()));
        boolean active = committing;
        if (!active && source != null && "sample".equals(source.kind()) && batches.size() < SAMPLE_KINDS) {
            List<Timestamp> syncs = jdbc.queryForList("""
                    select coalesce(last_sync_at, connected_at) from data_sources
                     where tenant_id = ? and kind = 'sample'
                    """, Timestamp.class, tenantId);
            if (!syncs.isEmpty() && syncs.getFirst() != null) {
                Instant synced = syncs.getFirst().toInstant();
                active = Duration.between(synced, now).abs().compareTo(CLAIM_GRACE) < 0;
            }
        }
        return new SampleLoading(active, batches);
    }

    // ---- rules ---------------------------------------------------------------

    private static List<Feature> features(CatalogueInfo catalogue, SalesInfo sales, PurchasesInfo purchases,
            PricesInfo prices, InventoryInfo inventory, SuppliersInfo suppliers, long deals, long priceablePairs,
            long performingSuppliers) {
        List<Feature> out = new ArrayList<>();
        boolean noBranch = catalogue.stores() == 0;
        String noBranchDetail = "Add a branch (any file with a Whse/Branch column, or a product master)";
        Consumer<Feature> add = f -> out.add(noBranch && !"history".equals(f.key())
                ? new Feature(f.key(), f.label(), "locked", List.of("products"), noBranchDetail)
                : f);

        String monthsOfSales = sales.months() + " months of your sales";
        // sell-pricing
        add.accept(sales.rows() > 0
                ? new Feature("sell-pricing", "Price recommendations", "ready", List.of(), "From " + monthsOfSales)
                : prices.itemsWithPrice() > 0
                        ? new Feature("sell-pricing", "Price recommendations", "partial", List.of("sales"),
                                "From your price list — add sales history for observed prices")
                        : new Feature("sell-pricing", "Price recommendations", "locked", List.of("sales", "products"),
                                "Upload sales history or set a price to get a recommendation"));
        // sell-forecast
        add.accept(sales.months() >= 6
                ? new Feature("sell-forecast", "Demand forecasts", "ready", List.of(), "From " + monthsOfSales)
                : sales.rows() > 0
                        ? new Feature("sell-forecast", "Demand forecasts", "partial", List.of("sales"),
                                sales.months() + " months of sales — 6 unlock forecasts")
                        : new Feature("sell-forecast", "Demand forecasts", "locked", List.of("sales"),
                                "Upload at least 6 months of sales history to unlock forecasts"));
        // sell-inventory
        add.accept(inventory.items() > 0 && sales.rows() > 0
                ? new Feature("sell-inventory", "Stock, weeks of cover & liquidation", "ready", List.of(),
                        "Stock on hand for " + inventory.items() + " items")
                : inventory.items() > 0
                        ? new Feature("sell-inventory", "Stock, weeks of cover & liquidation", "partial",
                                List.of("sales"), "Stock on hand loaded — add sales history for weeks of cover")
                        : new Feature("sell-inventory", "Stock, weeks of cover & liquidation", "locked",
                                List.of("products", "sales"), "Upload stock on hand to unlock weeks of cover and liquidation"));
        // buy-compare
        boolean compareReady = suppliers.count() > 0 && (purchases.rows() > 0 || prices.quotesOnFile() > 0);
        Feature buyCompare = compareReady
                ? new Feature("buy-compare", "Supplier comparison", "ready", List.of(),
                        suppliers.count() + " suppliers with quotes or purchase history")
                : suppliers.count() > 0
                        ? new Feature("buy-compare", "Supplier comparison", "partial", List.of("purchases", "products"),
                                suppliers.count() + " suppliers — add purchase history or supplier costs")
                        : new Feature("buy-compare", "Supplier comparison", "locked",
                                List.of("suppliers", "purchases", "products"),
                                "Add suppliers to compare quotes, lanes and lead times");
        add.accept(buyCompare);
        // buy-incumbent
        add.accept(purchases.rows() > 0
                ? new Feature("buy-incumbent", "Incumbent & annual units", "ready", List.of(),
                        "From " + purchases.rows() + " purchase lines")
                : prices.quotesOnFile() > 0
                        ? new Feature("buy-incumbent", "Incumbent & annual units", "partial", List.of("purchases"),
                                "From supplier price lists — add purchase history for the incumbent")
                        : new Feature("buy-incumbent", "Incumbent & annual units", "locked", List.of("purchases"),
                                "No purchase history for this item — pick a supplier to compare"));
        // bulk-sell
        Feature sellPricing = out.getFirst();
        add.accept("ready".equals(sellPricing.status()) && priceablePairs >= 2
                ? new Feature("bulk-sell", "Bulk repricing", "ready", List.of(), sellPricing.detail())
                : "partial".equals(sellPricing.status()) || ("ready".equals(sellPricing.status()) && priceablePairs < 2)
                        ? new Feature("bulk-sell", "Bulk repricing", "partial", List.of("sales", "products"),
                                priceablePairs < 2 ? "Fewer than two priceable item-branch pairs" : sellPricing.detail())
                        : new Feature("bulk-sell", "Bulk repricing", "locked", List.of("sales", "products"),
                                sellPricing.detail()));
        // bulk-buy
        add.accept(new Feature("bulk-buy", "Bulk sourcing", buyCompare.status(),
                "ready".equals(buyCompare.status()) ? List.of() : List.of("suppliers", "purchases"),
                buyCompare.detail()));
        // suppliers-performance
        add.accept(performingSuppliers > 0
                ? new Feature("suppliers-performance", "Supplier performance", "ready", List.of(),
                        "Observed from received purchase orders")
                : suppliers.count() > 0
                        ? new Feature("suppliers-performance", "Supplier performance", "partial", List.of("purchases"),
                                "Provided figures only — 5 received purchase orders per supplier unlock observed performance")
                        : new Feature("suppliers-performance", "Supplier performance", "locked",
                                List.of("purchases", "suppliers"), "Add suppliers to see their performance"));
        // insights-revenue
        add.accept(sales.rows() > 0
                ? new Feature("insights-revenue", "Revenue & margin", "ready", List.of(), "From " + monthsOfSales)
                : new Feature("insights-revenue", "Revenue & margin", "locked", List.of("sales"),
                        "Upload sales history to see revenue, margin and demand by region"));
        // insights-demographics
        add.accept(sales.withCustomer() > 0 && catalogue.customersUnassigned() < catalogue.customers()
                ? new Feature("insights-demographics", "Customer segments", "ready", List.of(),
                        (catalogue.customers() - catalogue.customersUnassigned()) + " customers tagged")
                : sales.rows() > 0
                        ? new Feature("insights-demographics", "Customer segments", "partial", List.of("sales"),
                                catalogue.customersUnassigned() + " customers need a segment")
                        : new Feature("insights-demographics", "Customer segments", "locked", List.of("sales"),
                                "Upload sales history to see customer segments"));
        // insights-geo
        add.accept(sales.rows() > 0 && catalogue.stores() - catalogue.storesUnassigned() > 0
                ? new Feature("insights-geo", "Regional view", "ready", List.of(),
                        (catalogue.stores() - catalogue.storesUnassigned()) + " branches placed")
                : sales.rows() > 0
                        ? new Feature("insights-geo", "Regional view", "partial", List.of("sales"),
                                "Place your branches in a region to unlock the regional view")
                        : new Feature("insights-geo", "Regional view", "locked", List.of("sales"),
                                "Upload sales history to see revenue, margin and demand by region"));
        // analytics-procurement
        add.accept(purchases.rows() > 0
                ? new Feature("analytics-procurement", "Procurement analytics", "ready", List.of(),
                        "From " + purchases.rows() + " purchase lines")
                : new Feature("analytics-procurement", "Procurement analytics", "locked", List.of("purchases"),
                        "Upload purchase history to see spend, savings and delivery performance"));
        // rfq
        add.accept(suppliers.count() > 0
                ? new Feature("rfq", "RFQs & awards", "ready", List.of(), suppliers.count() + " suppliers")
                : new Feature("rfq", "RFQs & awards", "locked", List.of("suppliers"), "Add suppliers to send an RFQ"));
        // history
        add.accept(deals > 0
                ? new Feature("history", "Decision history", "ready", List.of(), deals + " decisions")
                : new Feature("history", "Decision history", "partial", List.of(),
                        "Apply a recommendation to start your history"));
        return out;
    }

    private static List<NextStep> nextSteps(SourceInfo source, CatalogueInfo catalogue, SalesInfo sales,
            PurchasesInfo purchases, PricesInfo prices, InventoryInfo inventory, CompetitorInfo competitors,
            SuppliersInfo suppliers, SampleLoading sampleLoading) {
        List<NextStep> steps = new ArrayList<>();
        boolean sampleTenant = source != null && "sample".equals(source.kind());
        if (sampleTenant && sampleLoading.batches().isEmpty() && !sampleLoading.active()) {
            steps.add(new NextStep("load-sample", "Load the sample history",
                    "Sales, purchase orders, prices, stock and competitor prices for Hardin Supply Co",
                    "/app/data?action=load-sample", null));
        }
        if (catalogue.products() == 0 && sales.rows() == 0) {
            steps.add(new NextStep("connect", "Connect your data",
                    "Upload a sales history or a product master to open the workspace",
                    "/app/data?kind=sales", "sales"));
        }
        if (prices.itemsMissingPrice() > 0) {
            steps.add(new NextStep("set-prices", prices.itemsMissingPrice() + " products have no price yet",
                    "Set list prices from cost, category benchmarks and competitor prices",
                    "/app/sell/prices?scope=missing", null));
        }
        if (prices.itemsMissingCost() > 0) {
            steps.add(new NextStep("add-costs", prices.itemsMissingCost() + " products have no cost",
                    "Add costs to measure margin and get price suggestions", "/app/sell/prices?scope=all", null));
        }
        if (sales.rows() == 0) {
            steps.add(new NextStep("upload-sales", "Upload sales history",
                    "Unlock price recommendations, forecasts and revenue insights",
                    "/app/data?kind=sales", "sales"));
        }
        if (purchases.rows() == 0) {
            steps.add(new NextStep("upload-purchases", "Upload purchase history",
                    "Unlock incumbent suppliers, real landed costs and procurement analytics",
                    "/app/data?kind=purchases", "purchases"));
        }
        if (catalogue.storesUnassigned() > 0) {
            steps.add(new NextStep("place-branches", catalogue.storesUnassigned() + " branches need a region",
                    "Place them to unlock regional views and lane costs", "/app/stores", null));
        }
        if (catalogue.customersUnassigned() > 0) {
            steps.add(new NextStep("tag-customers", catalogue.customersUnassigned() + " customers need a segment",
                    "Tag them to unlock customer segments", "/app/customers", null));
        }
        if (inventory.items() == 0) {
            steps.add(new NextStep("upload-inventory", "Add stock on hand",
                    "Unlock weeks of cover and liquidation", "/app/data?kind=products", "products"));
        }
        if (competitors.observations() == 0) {
            steps.add(new NextStep("upload-competitors", "Add competitor prices",
                    "Anchor every recommendation to the market", "/app/data?kind=competitor_prices",
                    "competitor_prices"));
        }
        if (suppliers.count() == 0) {
            steps.add(new NextStep("add-suppliers", "Add suppliers",
                    "Unlock buy comparisons, RFQs and awards", "/app/suppliers", null));
        }
        return steps.size() <= 3 ? steps : List.copyOf(steps.subList(0, 3));
    }

    private long count(String sql, UUID tenantId) {
        Long value = jdbc.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0 : value;
    }

    private static LocalDate date(Date value) {
        return value == null ? null : value.toLocalDate();
    }
}
