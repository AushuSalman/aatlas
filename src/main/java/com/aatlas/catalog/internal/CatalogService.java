package com.aatlas.catalog.internal;

import com.aatlas.catalog.internal.reference.ReferenceDataRepository;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.common.web.CursorPage;
import com.aatlas.history.PriceList;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catalogue reads for the signed-in tenant.
 *
 * <p>Every read is a row lookup or a keyset page; nothing here walks item by store. The
 * tenant comes from the JWT and is a predicate on every query, with row-level security
 * behind it.
 *
 * <p>A tenant with no catalogue answers {@code 404 no_catalogue} rather than an empty
 * list. An empty list would look to the client like "nothing matched"; the truthful
 * answer is "connect a data source first", and the code lets the client route there.
 *
 * <p>Products, accounts and regions. Branches are {@link StoreService}'s, reads included,
 * because they can be written and the rules that decide what a branch may look like are
 * the same on the way in and on the way out.
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final StoreRepository stores;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final ReferenceDataRepository reference;
    private final PriceList priceList;
    private final AatlasClock clock;

    CatalogService(
            StoreRepository stores,
            ProductRepository products,
            CustomerRepository customers,
            ReferenceDataRepository reference,
            PriceList priceList,
            AatlasClock clock) {
        this.stores = stores;
        this.products = products;
        this.customers = customers;
        this.reference = reference;
        this.priceList = priceList;
        this.clock = clock;
    }

    // ---- products ----------------------------------------------------------------------

    CursorPage<ProductView> products(String q, String category, Boolean hasSales, Boolean priceable, int limit,
            String cursor) {
        UUID tenantId = Catalogues.requireCatalogue(stores);
        String after = Cursors.decode(cursor);
        // The other half of "priceable" next to has_sales, read through the history module's
        // price list (one distinct scan) so this and the sell gate cannot disagree.
        Set<UUID> priced = priceList.pricedProducts(clock.today());

        Specification<ProductEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantId"), tenantId));
            if (q != null && !q.isBlank()) {
                String pattern = "%" + Catalogues.escapeLike(q.strip().toLowerCase(Locale.ROOT)) + "%";
                where.add(cb.or(
                        cb.like(cb.lower(root.get("itemNumber")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
            }
            if (category != null && !category.isBlank()) {
                where.add(cb.equal(cb.lower(root.get("category")), category.strip().toLowerCase(Locale.ROOT)));
            }
            if (hasSales != null) {
                where.add(cb.equal(root.get("hasSales"), hasSales));
            }
            if (priceable != null) {
                // An empty IN list is not valid SQL, so a tenant with no priced product
                // reduces to the has_sales test alone.
                Predicate sells = cb.isTrue(root.get("hasSales"));
                Predicate isPriceable = priced.isEmpty() ? sells : cb.or(sells, root.get("id").in(priced));
                where.add(priceable ? isPriceable : cb.not(isPriceable));
            }
            if (after != null) {
                where.add(cb.greaterThan(root.get("itemNumber"), after));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        List<ProductView> rows = products
                .findBy(spec, fetch -> fetch.sortBy(Sort.by("itemNumber")).limit(limit + 1).all())
                .stream()
                .map(p -> ProductView.of(p, priced.contains(p.getId())))
                .toList();
        return CursorPage.of(rows, limit, row -> Cursors.encode(row.itemNumber()));
    }

    ProductDetailView product(String itemNumber) {
        UUID tenantId = Catalogues.requireCatalogue(stores);
        ProductEntity product = findProduct(tenantId, itemNumber);

        ProductDetailView.CommodityTrendView trend = reference.commodityTrend(product.getCommodity())
                .map(t -> new ProductDetailView.CommodityTrendView(t.pct90(), t.label()))
                .orElse(ProductDetailView.CommodityTrendView.NONE);
        List<String> storeIds = stores.findSellingProduct(tenantId, product.getId()).stream()
                .map(StoreEntity::getStoreCode)
                .toList();
        boolean hasPrice = priceList.current(product.getId(), null, clock.today())
                .map(c -> c.listPrice() != null && c.listPrice().signum() > 0)
                .orElse(false);
        return ProductDetailView.of(product, hasPrice, trend, storeIds);
    }

    List<StoreView> productStores(String itemNumber) {
        UUID tenantId = Catalogues.requireCatalogue(stores);
        ProductEntity product = findProduct(tenantId, itemNumber);
        return stores.findSellingProduct(tenantId, product.getId()).stream().map(StoreView::of).toList();
    }

    // ---- regions -----------------------------------------------------------------------

    List<RegionView> regions() {
        UUID tenantId = Catalogues.requireCatalogue(stores);
        // The catalogue's branches say which country this is; the seed put them all in one.
        String country = stores.findCountries(tenantId).stream().sorted().findFirst()
                .orElseThrow(Catalogues::noCatalogue);

        Map<String, List<String>> storeCodesByRegion = new LinkedHashMap<>();
        for (StoreEntity store : stores.findByTenantIdOrderByStoreCode(tenantId)) {
            storeCodesByRegion.computeIfAbsent(store.getRegionKey(), unused -> new ArrayList<>())
                    .add(store.getStoreCode());
        }

        return reference.regionsOf(country).stream()
                .map(region -> new RegionView(
                        region.key(),
                        region.label(),
                        region.shortLabel(),
                        region.codes(),
                        region.subdivisions().stream()
                                .map(s -> new RegionView.SubdivisionView(s.code(), s.name()))
                                .toList(),
                        List.copyOf(storeCodesByRegion.getOrDefault(region.key(), List.of()))))
                .toList();
    }

    // ---- customers ---------------------------------------------------------------------

    CursorPage<CustomerView> customers(int limit, String cursor) {
        UUID tenantId = Catalogues.requireCatalogue(stores);
        String after = Cursors.decode(cursor);

        Specification<CustomerEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantId"), tenantId));
            if (after != null) {
                where.add(cb.greaterThan(root.get("code"), after));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        List<CustomerView> rows = customers
                .findBy(spec, fetch -> fetch.sortBy(Sort.by("code")).limit(limit + 1).all())
                .stream()
                .map(CustomerView::of)
                .toList();
        return CursorPage.of(rows, limit, row -> Cursors.encode(row.id()));
    }

    CustomerView customer(String idOrCode) {
        UUID tenantId = Catalogues.requireCatalogue(stores);
        return Catalogues.findByCodeOrId(idOrCode,
                code -> customers.findByTenantIdAndCode(tenantId, code),
                id -> customers.findByTenantIdAndId(tenantId, id))
                .map(CustomerView::of)
                .orElseThrow(() -> ApiException.notFound("Customer", idOrCode));
    }

    // ---- reference ---------------------------------------------------------------------

    /** Public and identical for every tenant; no catalogue required. */
    public ReferenceDataRepository.Logistics logistics() {
        return reference.logistics();
    }

    // ---- helpers -----------------------------------------------------------------------

    private ProductEntity findProduct(UUID tenantId, String itemNumber) {
        return products.findByTenantIdAndItemNumber(tenantId, itemNumber.strip())
                .orElseThrow(() -> ApiException.notFound("Product", itemNumber));
    }
}
