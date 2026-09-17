package com.aatlas.suppliers.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Stars, derived from what the platform already measures. Mirrors the weighted mean the
 * frontend's {@code intel/suppliers.ts} computes; stored rather than derived on read so the
 * panel is a sorted scan.
 *
 * <p>Primary key is the supplier's own id; see {@link SupplierTermsEntity} for why.
 */
@Entity
@Table(name = "supplier_ratings")
@EntityListeners(AuditingEntityListener.class)
class SupplierRatingEntity {

    @Id
    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "rating", nullable = false)
    private double rating;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    @Column(name = "quality")
    private Double quality;

    @Column(name = "delivery")
    private Double delivery;

    @Column(name = "communication")
    private Double communication;

    @Column(name = "pricing")
    private Double pricing;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "rating_source", nullable = false)
    private String ratingSource;

    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SupplierRatingEntity() {
        // JPA
    }

    SupplierRatingEntity(UUID supplierId, UUID tenantId, double rating, int reviewCount,
            RatingBreakdown breakdown, String label, String ratingSource, Instant computedAt) {
        this.supplierId = supplierId;
        this.tenantId = tenantId;
        this.rating = rating;
        this.reviewCount = reviewCount;
        this.quality = breakdown.quality();
        this.delivery = breakdown.delivery();
        this.communication = breakdown.communication();
        this.pricing = breakdown.pricing();
        this.label = label;
        this.ratingSource = ratingSource;
        this.computedAt = computedAt;
    }

    UUID getSupplierId() {
        return supplierId;
    }

    double getRating() {
        return rating;
    }

    int getReviewCount() {
        return reviewCount;
    }

    RatingBreakdown toBreakdown() {
        return new RatingBreakdown(quality, delivery, communication, pricing);
    }

    String getLabel() {
        return label;
    }

    String getRatingSource() {
        return ratingSource;
    }
    /** Re-derives the stars after an import supplied new performance numbers. */
    void recompute(double rating, RatingBreakdown breakdown, String label, String ratingSource, Instant computedAt) {
        this.rating = rating;
        this.quality = breakdown.quality();
        this.delivery = breakdown.delivery();
        this.communication = breakdown.communication();
        this.pricing = breakdown.pricing();
        this.label = label;
        this.ratingSource = ratingSource;
        this.computedAt = computedAt;
    }
}
