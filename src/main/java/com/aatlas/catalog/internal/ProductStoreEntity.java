package com.aatlas.catalog.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An item's sales history at a branch: the row whose presence makes the pair priceable.
 *
 * <p>Plain ids rather than {@code @ManyToOne}: the two sides are only ever joined by the
 * one query that lists a product's branches, and a navigable association would invite
 * lazy loads on the request thread that the N+1 guard in {@code application.yml} then
 * has to catch.
 */
@Entity
@Table(name = "product_stores")
public class ProductStoreEntity extends TenantScopedEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "sells", nullable = false)
    private boolean sells = true;

    @Column(name = "first_sale_at")
    private LocalDate firstSaleAt;

    @Column(name = "last_sale_at")
    private LocalDate lastSaleAt;

    protected ProductStoreEntity() {
        // JPA
    }

    ProductStoreEntity(UUID tenantId, UUID productId, UUID storeId, LocalDate firstSaleAt, LocalDate lastSaleAt) {
        setTenantId(tenantId);
        this.productId = productId;
        this.storeId = storeId;
        this.sells = true;
        this.firstSaleAt = firstSaleAt;
        this.lastSaleAt = lastSaleAt;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public boolean isSells() {
        return sells;
    }

    public LocalDate getFirstSaleAt() {
        return firstSaleAt;
    }

    public LocalDate getLastSaleAt() {
        return lastSaleAt;
    }
}
