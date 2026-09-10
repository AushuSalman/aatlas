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
 * A supplier on the tenant's panel: the seeded eight, plus anything added from a lookup.
 *
 * <p>{@code supplierKey} is the frontend id ({@code sup-2}, {@code cus-f26j4f}). Every
 * seeded figure the Buy and Suppliers screens derive for a supplier hashes this key, so it
 * is stored rather than replaced by the row's own uuid, and never changes once assigned.
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

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "contact_name", nullable = false)
    private String contactName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    @Column(name = "otif_pct", nullable = false)
    private double otifPct;

    @Column(name = "price_index", nullable = false)
    private double priceIndex;

    @Column(name = "defect_pct", nullable = false)
    private double defectPct;

    @Column(name = "holds_stock", nullable = false)
    private boolean holdsStock;

    @Column(name = "years_trading", nullable = false)
    private int yearsTrading;

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

    @Column(name = "since", nullable = false)
    private LocalDate since;

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
            int leadTimeDays,
            double otifPct,
            double priceIndex,
            double defectPct,
            boolean holdsStock,
            int yearsTrading,
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

    int getLeadTimeDays() {
        return leadTimeDays;
    }

    double getOtifPct() {
        return otifPct;
    }

    double getPriceIndex() {
        return priceIndex;
    }

    double getDefectPct() {
        return defectPct;
    }

    boolean isHoldsStock() {
        return holdsStock;
    }

    int getYearsTrading() {
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

    LocalDate getSince() {
        return since;
    }
}
