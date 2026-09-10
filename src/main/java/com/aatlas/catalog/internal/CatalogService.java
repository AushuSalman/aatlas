package com.aatlas.catalog.internal;

import com.aatlas.catalog.internal.reference.ReferenceDataRepository;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.web.CursorPage;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
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
 */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final StoreRepository stores;
    private final ProductRepository products;
    private final CustomerRepository customers;
    private final ReferenceDataRepository reference;

    CatalogService(
            StoreRepository stores,
            ProductRepository products,
            CustomerRepository customers,
            ReferenceDataRepository reference) {
        this.stores = stores;
        this.products = products;
        this.customers = customers;
        this.reference = reference;
    }

    // ---- products ----------------------------------------------------------------------

    CursorPage<ProductView> products(String q, String category, Boolean hasSales, int limit, String cursor) {
        UUID tenantId = requireCatalogue();
        String after = Cursors.decode(cursor);

        Specification<ProductEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantId"), tenantId));
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q.strip().toLowerCase(Locale.ROOT)) + "%";
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
            if (after != null) {
                where.add(cb.greaterThan(root.get("itemNumber"), after));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        List<ProductView> rows = products
                .findBy(spec, fetch -> fetch.sortBy(Sort.by("itemNumber")).limit(limit + 1).all())
                .stream()
                .map(ProductView::of)
                .toList();
        return CursorPage.of(rows, limit, row -> Cursors.encode(row.itemNumber()));
    }

    ProductDetailView product(String itemNumber) {
        UUID tenantId = requireCatalogue();
        ProductEntity product = findProduct(tenantId, itemNumber);

        ProductDetailView.CommodityTrendView trend = reference.commodityTrend(product.getCommodity())
                .map(t -> new ProductDetailView.CommodityTrendView(t.pct90(), t.label()))
                .orElse(ProductDetailView.CommodityTrendView.NONE);
        List<String> storeIds = stores.findSellingProduct(tenantId, product.getId()).stream()
                .map(StoreEntity::getStoreCode)
                .toList();
        return ProductDetailView.of(product, trend, storeIds);
    }

    List<StoreView> productStores(String itemNumber) {
        UUID tenantId = requireCatalogue();
        ProductEntity product = findProduct(tenantId, itemNumber);
        return stores.findSellingProduct(tenantId, product.getId()).stream().map(StoreView::of).toList();
    }

    // ---- stores ------------------------------------------------------------------------

    CursorPage<StoreView> stores(String region, int limit, String cursor) {
        UUID tenantId = requireCatalogue();
        String after = Cursors.decode(cursor);

        Specification<StoreEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(root.get("tenantId"), tenantId));
            if (region != null && !region.isBlank()) {
                where.add(cb.equal(root.get("regionKey"), region.strip().toLowerCase(Locale.ROOT)));
            }
            if (after != null) {
                where.add(cb.greaterThan(root.get("storeCode"), after));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };

        List<StoreView> rows = stores
                .findBy(spec, fetch -> fetch.sortBy(Sort.by("storeCode")).limit(limit + 1).all())
                .stream()
                .map(StoreView::of)
                .toList();
        return CursorPage.of(rows, limit, row -> Cursors.encode(row.storeId()));
    }

    StoreView store(String idOrCode) {
        UUID tenantId = requireCatalogue();
        return findByCodeOrId(idOrCode,
                code -> stores.findByTenantIdAndStoreCode(tenantId, code),
                id -> stores.findByTenantIdAndId(tenantId, id))
                .map(StoreView::of)
                .orElseThrow(() -> ApiException.notFound("Store", idOrCode));
    }

    // ---- regions -----------------------------------------------------------------------

    List<RegionView> regions() {
        UUID tenantId = requireCatalogue();
        // The catalogue's branches say which country this is; the seed put them all in one.
        String country = stores.findCountries(tenantId).stream().sorted().findFirst()
                .orElseThrow(CatalogService::noCatalogue);

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
        UUID tenantId = requireCatalogue();
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
        UUID tenantId = requireCatalogue();
        return findByCodeOrId(idOrCode,
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

    /** The tenant from the JWT, or 404 no_catalogue if it has nothing to read yet. */
    private UUID requireCatalogue() {
        UUID tenantId = TenantContext.requireTenantId();
        if (!stores.existsByTenantId(tenantId)) {
            throw noCatalogue();
        }
        return tenantId;
    }

    static ApiException noCatalogue() {
        return new ApiException(HttpStatus.NOT_FOUND, "no_catalogue",
                "This workspace has no catalogue yet. Connect a data source with "
                        + "POST /api/v1/data-sources - {\"kind\":\"sample\"} is the quickest way to see "
                        + "every screen - or import history with POST /api/v1/imports.");
    }

    private ProductEntity findProduct(UUID tenantId, String itemNumber) {
        return products.findByTenantIdAndItemNumber(tenantId, itemNumber.strip())
                .orElseThrow(() -> ApiException.notFound("Product", itemNumber));
    }

    /**
     * The frontend addresses branches and accounts by their codes ({@code 100959},
     * {@code c-1}); a generic client may use our uuid. Codes win, and a value that is not
     * a code is only then tried as a uuid.
     */
    private static <T> Optional<T> findByCodeOrId(
            String idOrCode, Function<String, Optional<T>> byCode, Function<UUID, Optional<T>> byId) {
        String value = idOrCode == null ? "" : idOrCode.strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Optional<T> found = byCode.apply(value);
        if (found.isPresent()) {
            return found;
        }
        try {
            return byId.apply(UUID.fromString(value));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
