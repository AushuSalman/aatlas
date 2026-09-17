package com.aatlas.history;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant's catalogue as the read layer sees it: products, branches, customers and the
 * regions they sit in. The one place codes become ids.
 */
public interface Catalogue {

    Optional<ProductRef> product(String itemNumber);

    Optional<ProductRef> productById(UUID id);

    List<ProductRef> products();

    Optional<StoreRef> store(String storeCode);

    Optional<StoreRef> storeById(UUID id);

    /** Active branches, in store-code order. */
    List<StoreRef> stores();

    Optional<CustomerRef> customer(String code);

    List<CustomerRef> customers();

    List<RegionRef> regions();

    Optional<RegionRef> region(String key);

    /** {@code US} or {@code UK}; {@code US} when the tenant has no branches yet. */
    String country();

    CatalogueCoverage coverage();

    record ProductRef(UUID id, String itemNumber, String description, String shortName, String category,
            String subcategory, String commodity, String unit, boolean hasSales, String defaultStoreCode,
            String source) {
    }

    /**
     * A branch.
     *
     * @param regionKey one of the four market regions, or {@code unassigned}
     * @param rpp regional price parity, 100 = national average; null = priced nationally
     */
    record StoreRef(UUID id, String storeCode, String legalName, String country, String subdivisionCode,
            String msaName, BigDecimal rpp, Integer txns, String segment, String regionKey, boolean active,
            String source) {

        /** "Dallas #100959" - the label every screen uses for a branch. */
        public String label() {
            String base = msaName != null && !msaName.isBlank()
                    ? msaName.split("-")[0].strip()
                    : legalName.split("-")[0].strip();
            return base + " #" + storeCode;
        }
    }

    record CustomerRef(UUID id, String code, String name, String segment, String tier, BigDecimal agreedDiscountPct,
            int typicalQty, String profile, int slaDays) {
    }

    record RegionRef(String key, String label, String shortLabel) {
    }

    record CatalogueCoverage(int products, int stores, int customers, int storesUnassigned,
            int customersUnassigned) {
    }
}
