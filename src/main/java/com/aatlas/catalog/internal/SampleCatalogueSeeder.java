package com.aatlas.catalog.internal;

import com.aatlas.catalog.CatalogSeeding;
import com.aatlas.catalog.internal.seed.SeedFiles;
import com.aatlas.catalog.internal.seed.SellersRule;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.tenant.CountryCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Copies the seeded catalogue into a tenant. The only writer of the catalogue tables
 * until imports and ERP sync arrive.
 *
 * <p>Which branches sell which item follows the frontend's rule exactly (see
 * {@link SellersRule}); the demo story and the step-2 golden files depend on it.
 *
 * <p>{@link Propagation#MANDATORY}: seeding never runs on its own. The caller is creating
 * the data-source row that says "this tenant has a catalogue", and the two must commit
 * together - a catalogue with no source row would be invisible to the workspace gate, and
 * a source row with no catalogue would open a workspace with nothing in it.
 */
@Service
class SampleCatalogueSeeder implements CatalogSeeding {

    private static final Logger log = LoggerFactory.getLogger(SampleCatalogueSeeder.class);

    /** The connect screen promises "twelve months of history". */
    private static final int HISTORY_MONTHS = 12;

    private final SeedFiles seeds;
    private final StoreRepository stores;
    private final ProductRepository products;
    private final ProductStoreRepository productStores;
    private final CustomerRepository customers;
    private final AatlasClock clock;

    SampleCatalogueSeeder(
            SeedFiles seeds,
            StoreRepository stores,
            ProductRepository products,
            ProductStoreRepository productStores,
            CustomerRepository customers,
            AatlasClock clock) {
        this.seeds = seeds;
        this.stores = stores;
        this.products = products;
        this.productStores = productStores;
        this.customers = customers;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SeedSummary seedSampleCatalogue(UUID tenantId, CountryCode country) {
        if (stores.existsByTenantId(tenantId)) {
            // Reconnecting after a disconnect, or a retry: the catalogue is already there.
            return SeedSummary.existing(
                    (int) stores.countByTenantId(tenantId),
                    (int) products.countByTenantId(tenantId),
                    (int) productStores.countByTenantId(tenantId),
                    (int) customers.countByTenantId(tenantId));
        }

        List<SeedFiles.SeedStore> seedStores = seeds.stores().get(country.name());
        if (seedStores == null || seedStores.isEmpty()) {
            throw new IllegalStateException("stores.json has no branches for country " + country);
        }

        // Branches first: the history rows need their ids. Insertion order is the seed's
        // order, which is also the order the sellers rule indexes by.
        Map<String, StoreEntity> storesByCode = new LinkedHashMap<>();
        for (SeedFiles.SeedStore s : seedStores) {
            SeedFiles.SeedMap map = s.map();
            storesByCode.put(s.storeId(), new StoreEntity(
                    tenantId,
                    s.storeId(),
                    s.companyNumber(),
                    s.legalName(),
                    s.country() == null ? country.name() : s.country(),
                    s.state(),
                    s.msaName(),
                    s.rpp(),
                    s.txns(),
                    s.itemCount(),
                    s.segment(),
                    s.regionKey(),
                    map == null ? null : map.x(),
                    map == null ? null : map.y(),
                    map == null ? null : map.anchor(),
                    StoreEntity.Source.SAMPLE));
        }
        stores.saveAll(storesByCode.values());
        List<String> codesInSeedOrder = List.copyOf(storesByCode.keySet());

        List<ProductEntity> productRows = new ArrayList<>();
        for (SeedFiles.SeedProduct p : seeds.products()) {
            productRows.add(new ProductEntity(
                    tenantId,
                    p.itemNumber(),
                    p.description(),
                    p.shortName() == null ? p.description() : p.shortName(),
                    p.category() == null ? "Plumbing" : p.category(),
                    p.subcategory() == null ? "Other" : p.subcategory(),
                    p.commodity() == null ? "none" : p.commodity(),
                    p.unit() == null ? "each" : p.unit(),
                    p.sellable(),
                    p.defaultTenant()));
        }
        products.saveAll(productRows);

        LocalDate today = clock.today();
        LocalDate historyStart = today.minusMonths(HISTORY_MONTHS);
        List<ProductStoreEntity> historyRows = new ArrayList<>();
        Map<String, SeedFiles.SeedProduct> seedByItem = new LinkedHashMap<>();
        seeds.products().forEach(p -> seedByItem.put(p.itemNumber(), p));
        for (ProductEntity product : productRows) {
            SeedFiles.SeedProduct seed = seedByItem.get(product.getItemNumber());
            for (String code : SellersRule.sellers(
                    product.getItemNumber(), seed.defaultTenant(), seed.sellable(), codesInSeedOrder)) {
                StoreEntity store = storesByCode.get(code);
                if (store != null) {
                    historyRows.add(new ProductStoreEntity(
                            tenantId, product.getId(), store.getId(), historyStart, today));
                }
            }
        }
        productStores.saveAll(historyRows);

        List<CustomerEntity> customerRows = new ArrayList<>();
        for (SeedFiles.SeedCustomer c : seeds.customers()) {
            customerRows.add(new CustomerEntity(
                    tenantId,
                    c.id(),
                    c.name(),
                    c.segment(),
                    c.tier(),
                    c.agreedDiscountPct(),
                    c.typicalQty() == null ? 1 : c.typicalQty(),
                    c.profile(),
                    c.slaDays() == null ? 0 : c.slaDays(),
                    c.note()));
        }
        customers.saveAll(customerRows);

        log.info("Seeded sample catalogue for tenant {} ({}): {} branches, {} products, {} history rows, {} accounts",
                tenantId, country, storesByCode.size(), productRows.size(), historyRows.size(), customerRows.size());
        return new SeedSummary(
                storesByCode.size(), productRows.size(), historyRows.size(), customerRows.size(), false);
    }
}
