package com.aatlas.catalog.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** An item in the master. See {@code V7} for what each column means. */
@Entity
@Table(name = "products")
public class ProductEntity extends TenantScopedEntity {

    @Column(name = "item_number", nullable = false, updatable = false)
    private String itemNumber;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "short_name", nullable = false)
    private String shortName;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "subcategory", nullable = false)
    private String subcategory;

    @Column(name = "commodity", nullable = false)
    private String commodity;

    @Column(name = "unit", nullable = false)
    private String unit;

    @Column(name = "has_sales", nullable = false)
    private boolean hasSales;

    @Column(name = "default_store_code")
    private String defaultStoreCode;

    protected ProductEntity() {
        // JPA
    }

    ProductEntity(
            UUID tenantId,
            String itemNumber,
            String description,
            String shortName,
            String category,
            String subcategory,
            String commodity,
            String unit,
            boolean hasSales,
            String defaultStoreCode) {
        setTenantId(tenantId);
        this.itemNumber = itemNumber;
        this.description = description;
        this.shortName = shortName;
        this.category = category;
        this.subcategory = subcategory;
        this.commodity = commodity;
        this.unit = unit;
        this.hasSales = hasSales;
        this.defaultStoreCode = defaultStoreCode;
    }

    public String getItemNumber() {
        return itemNumber;
    }

    public String getDescription() {
        return description;
    }

    public String getShortName() {
        return shortName;
    }

    public String getCategory() {
        return category;
    }

    public String getSubcategory() {
        return subcategory;
    }

    public String getCommodity() {
        return commodity;
    }

    public String getUnit() {
        return unit;
    }

    public boolean isHasSales() {
        return hasSales;
    }

    public String getDefaultStoreCode() {
        return defaultStoreCode;
    }
}
