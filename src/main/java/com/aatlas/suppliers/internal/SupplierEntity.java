package com.aatlas.suppliers.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A supplier on the tenant's panel: the seeded eight, plus anything added from a lookup, a
 * file, the form, or a purchase-order import.
 *
 * <p>{@code supplierKey} is the frontend id ({@code sup-2}, {@code cus-f26j4f}, {@code po-…}).
 * It is stored rather than replaced by the row's own uuid, and never changes once assigned.
 *
 * <p>The performance figures are boxed: a supplier known only from a purchase order has no
 * on-time rate, lead time, price index or defect rate on file, and {@code null} is that
 * state. Nothing here or downstream may substitute a number for it.
 */
@Entity
@Table(name = "suppliers")
class SupplierEntity extends TenantScopedEntity {

    @Column(name = "supplier_key", nullable = false, updatable = false)
    private String supplierKey;

    @Column(name = "vendor_code", nullable = false)
    private String vendorCode;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "country", nullable = false)
    private String country;

    @Column(name = "city", nullable = false)
    private String city;

    @Column(name = "website", nullable = false)
    private String website;

    @Column(name = "category")
    private String category;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "otif_pct")
    private Double otifPct;

    @Column(name = "price_index")
    private Double priceIndex;

    @Column(name = "defect_pct")
    private Double defectPct;

    @Column(name = "holds_stock")
    private Boolean holdsStock;

    @Column(name = "years_trading")
    private Integer yearsTrading;

    @Column(name = "spend_share_12m", nullable = false)
    private double spendShare12m;

    @Column(name = "spend_ytd", nullable = false)
    private BigDecimal spendYtd;

    @Column(name = "po_count_12m", nullable = false)
    private int poCount12m;

    @Column(name = "is_custom", nullable = false)
    private boolean custom;

    @Column(name = "added_by")
    private UUID addedBy;

    @Column(name = "added_at")
    private Instant addedAt;

    @Column(name = "since")
    private LocalDate since;

    @Column(name = "source")
    private String source;

    protected SupplierEntity() {
        // JPA
    }

    SupplierEntity(
            String supplierKey,
            String vendorCode,
            String name,
            String country,
            String city,
            String website,
            String category,
            String contactName,
            String email,
            String currency,
            Integer leadTimeDays,
            Double otifPct,
            Double priceIndex,
            Double defectPct,
            Boolean holdsStock,
            Integer yearsTrading,
            double spendShare12m,
            BigDecimal spendYtd,
            int poCount12m,
            boolean custom,
            UUID addedBy,
            Instant addedAt,
            LocalDate since) {
        this.supplierKey = supplierKey;
        this.vendorCode = vendorCode;
        this.name = name;
        this.country = country;
        this.city = city;
        this.website = website;
        this.category = category;
        this.contactName = contactName;
        this.email = email;
        this.currency = currency;
        this.leadTimeDays = leadTimeDays;
        this.otifPct = otifPct;
        this.priceIndex = priceIndex;
        this.defectPct = defectPct;
        this.holdsStock = holdsStock;
        this.yearsTrading = yearsTrading;
        this.spendShare12m = spendShare12m;
        this.spendYtd = spendYtd;
        this.poCount12m = poCount12m;
        this.custom = custom;
        this.addedBy = addedBy;
        this.addedAt = addedAt;
        this.since = since;
    }

    String getSupplierKey() {
        return supplierKey;
    }

    String getVendorCode() {
        return vendorCode;
    }

    String getName() {
        return name;
    }

    String getCountry() {
        return country;
    }

    String getCity() {
        return city;
    }

    String getWebsite() {
        return website;
    }

    /** Null for a supplier created from a purchase order, until someone classifies it. */
    String getCategory() {
        return category;
    }

    String getContactName() {
        return contactName;
    }

    void setContactName(String contactName) {
        this.contactName = contactName;
    }

    String getEmail() {
        return email;
    }

    void setEmail(String email) {
        this.email = email;
    }

    void setCategory(String category) {
        this.category = category;
    }

    String getCurrency() {
        return currency;
    }

    /** Null = not provided. */
    Integer getLeadTimeDays() {
        return leadTimeDays;
    }

    /** Null = not provided. */
    Double getOtifPct() {
        return otifPct;
    }

    /** Null = not provided. */
    Double getPriceIndex() {
        return priceIndex;
    }

    /** Null = not provided. */
    Double getDefectPct() {
        return defectPct;
    }

    /** Null = not provided. */
    Boolean getHoldsStock() {
        return holdsStock;
    }

    boolean isHoldsStock() {
        return Boolean.TRUE.equals(holdsStock);
    }

    /** Null = not provided. */
    Integer getYearsTrading() {
        return yearsTrading;
    }

    double getSpendShare12m() {
        return spendShare12m;
    }

    BigDecimal getSpendYtd() {
        return spendYtd;
    }

    int getPoCount12m() {
        return poCount12m;
    }

    boolean isCustom() {
        return custom;
    }

    UUID getAddedBy() {
        return addedBy;
    }

    Instant getAddedAt() {
        return addedAt;
    }

    /** Null when the platform has never observed an order from them. */
    LocalDate getSince() {
        return since;
    }

    String getSource() {
        return source;
    }

    void setSource(String source) {
        this.source = source;
    }

    /**
     * Overwrites the profile and performance numbers from a re-imported file.
     *
     * <p>Deliberately does not touch spend, order count or {@code since}: those are facts
     * about trading with this supplier that the platform observed, and a vendor-master
     * export has no business overwriting them.
     */
    void applyImport(
            String name,
            String country,
            String city,
            String website,
            String category,
            String contactName,
            String email,
            String currency,
            Integer leadTimeDays,
            Double otifPct,
            Double priceIndex,
            Double defectPct,
            Boolean holdsStock,
            Integer yearsTrading) {
        this.name = name;
        this.country = country;
        this.city = city;
        this.website = website;
        this.category = category;
        this.contactName = contactName;
        this.email = email;
        this.currency = currency;
        this.leadTimeDays = leadTimeDays;
        this.otifPct = otifPct;
        this.priceIndex = priceIndex;
        this.defectPct = defectPct;
        this.holdsStock = holdsStock;
        this.yearsTrading = yearsTrading;
    }
}
