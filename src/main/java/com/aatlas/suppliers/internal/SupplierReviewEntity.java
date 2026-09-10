package com.aatlas.suppliers.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One thing a buyer said. Mirrors {@code SupplierReview} in the frontend's {@code intel/suppliers.ts}. */
@Entity
@Table(name = "supplier_reviews")
class SupplierReviewEntity extends TenantScopedEntity {

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "author", nullable = false)
    private String author;

    @Column(name = "when_label", nullable = false)
    private String whenLabel;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "stars", nullable = false)
    private int stars;

    @Column(name = "text", nullable = false)
    private String text;

    @Column(name = "source", nullable = false)
    private String source;

    protected SupplierReviewEntity() {
        // JPA
    }

    SupplierReviewEntity(UUID supplierId, int position, SupplierReview review, String source) {
        this.supplierId = supplierId;
        this.position = position;
        this.author = review.author();
        this.whenLabel = review.when();
        this.stars = review.stars();
        this.text = review.text();
        this.source = source;
    }

    UUID getSupplierId() {
        return supplierId;
    }

    int getPosition() {
        return position;
    }

    SupplierReview toReview() {
        return new SupplierReview(author, whenLabel, stars, text);
    }
}
