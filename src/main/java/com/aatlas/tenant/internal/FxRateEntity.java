package com.aatlas.tenant.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Units of {@code quote} per one unit of {@code base} on {@code asOf}.
 *
 * <p>Reference data shared by every tenant, so no {@code tenant_id}. Rows are never
 * updated: a nightly job inserts a new {@code asOf} and readers take the newest per pair.
 */
@Entity
@Table(name = "fx_rate")
public class FxRateEntity {

    @EmbeddedId
    private Key key;

    @Column(name = "rate", nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(name = "source", nullable = false, insertable = false, updatable = false)
    private String source;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected FxRateEntity() {
        // JPA
    }

    public Key getKey() {
        return key;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getSource() {
        return source;
    }

    /** {@code (base, quote, as_of)}: the primary key. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "base", nullable = false, length = 3)
        private String base;

        @Column(name = "quote", nullable = false, length = 3)
        private String quote;

        @Column(name = "as_of", nullable = false)
        private LocalDate asOf;

        protected Key() {
            // JPA
        }

        public String getBase() {
            return base;
        }

        public String getQuote() {
            return quote;
        }

        public LocalDate getAsOf() {
            return asOf;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && Objects.equals(base, that.base)
                    && Objects.equals(quote, that.quote)
                    && Objects.equals(asOf, that.asOf);
        }

        @Override
        public int hashCode() {
            return Objects.hash(base, quote, asOf);
        }
    }
}
