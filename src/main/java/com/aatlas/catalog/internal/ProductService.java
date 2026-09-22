package com.aatlas.catalog.internal;

import com.aatlas.catalog.internal.reference.ReferenceDataRepository;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.history.PriceBook;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes to the item master: adding one item by hand.
 *
 * <p>The same rules as a new item in the products import, so an item typed in and an item
 * loaded from a file are indistinguishable downstream: blank category and subcategory are
 * {@code uncategorised}, a blank unit is {@code each}, a commodity must be one the platform
 * tracks, and item numbers are unique per company ignoring case. A price or cost goes to the
 * price list tenant-wide through {@link PriceBook}, the one way either is ever set.
 *
 * <p>Like opening a branch, this does not require a connected data source: typing the first
 * item in is a legitimate way to start a catalogue.
 */
@Service
@Transactional(readOnly = true)
class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);
    private static final String UNCATEGORISED = "uncategorised";

    private final ProductRepository products;
    private final ReferenceDataRepository reference;
    private final PriceBook priceBook;
    private final StoreAccess access;

    ProductService(ProductRepository products, ReferenceDataRepository reference, PriceBook priceBook,
            StoreAccess access) {
        this.products = products;
        this.reference = reference;
        this.priceBook = priceBook;
        this.access = access;
    }

    /**
     * @throws ApiException 403 not_allowed, 409 item_number_taken, 400 unknown_commodity
     */
    @Transactional
    ProductView create(CreateProductRequest request) {
        access.requireProductEditor();
        UUID tenantId = TenantContext.requireTenantId();

        String item = request.itemNumber().strip();
        if (products.existsByTenantIdAndItemNumberIgnoreCase(tenantId, item)) {
            throw taken(item);
        }
        String description = request.description().strip();

        ProductEntity product = ProductEntity.manual(
                tenantId,
                item,
                description,
                shortName(description),
                orDefault(request.category(), UNCATEGORISED),
                orDefault(request.subcategory(), UNCATEGORISED),
                commodity(request.commodity()),
                orDefault(request.unit(), "each"));
        ProductEntity saved = save(product, item);

        // Flushed above, so the price book's own lookup of the item finds this row.
        if (request.listPrice() != null || request.unitCost() != null) {
            priceBook.write(
                    List.of(new PriceBook.PriceWrite(item, null, request.listPrice(), request.unitCost(),
                            LocalDate.now(), null)),
                    "manual",
                    TenantContext.currentUserId().orElse(null),
                    null);
        }

        log.info("Product {} added to tenant {} by user {}", item, tenantId,
                TenantContext.currentUserId().orElse(null));
        return ProductView.of(saved, request.listPrice() != null && request.listPrice().signum() > 0);
    }

    /** Turns the unique index on (tenant, item number) into the 409 it means when two requests race. */
    private ProductEntity save(ProductEntity product, String item) {
        try {
            return products.saveAndFlush(product);
        } catch (DataIntegrityViolationException clash) {
            throw taken(item);
        }
    }

    /** A tracked commodity key, or {@code none}. Spelling and case are forgiven; an unknown one is refused. */
    private String commodity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "none";
        }
        String key = raw.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (key.equals("none") || reference.commodityTrend(key).isPresent()) {
            return key;
        }
        throw ApiException.badRequest("unknown_commodity",
                "\"" + raw.strip() + "\" is not a commodity the platform tracks. Leave it blank or pick one from the list.");
    }

    private static ApiException taken(String item) {
        return ApiException.conflict("item_number_taken",
                "Item " + item + " already exists. Item numbers are unique per company.");
    }

    /** The import's rule, so a typed-in item reads the same as a loaded one. */
    private static String shortName(String description) {
        return description.length() <= 40 ? description : description.substring(0, 40).strip();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
