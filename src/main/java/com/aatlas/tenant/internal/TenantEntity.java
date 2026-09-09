package com.aatlas.tenant.internal;

import com.aatlas.common.persistence.BaseEntity;
import com.aatlas.tenant.CountryCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A customer company.
 *
 * <p>Extends {@link BaseEntity} and not {@code TenantScopedEntity}: this is the row every
 * other table's {@code tenant_id} points at, so it cannot itself be scoped by one.
 */
@Entity
@Table(name = "tenants")
public class TenantEntity extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, updatable = false)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "country", nullable = false, length = 2)
    private CountryCode country;

    /**
     * Denormalised from {@link CountryCode#tradingCurrency()} on purpose. Reports and
     * exports read the currency a figure was recorded in; deriving it from the country
     * at read time would silently rewrite history if a tenant ever moved country.
     */
    @Column(name = "trading_currency", nullable = false, length = 3)
    private String tradingCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TenantStatus status = TenantStatus.ACTIVE;

    protected TenantEntity() {
        // JPA
    }

    TenantEntity(String name, String slug, CountryCode country) {
        this.name = name;
        this.slug = slug;
        this.country = country;
        this.tradingCurrency = country.tradingCurrency();
        this.status = TenantStatus.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public CountryCode getCountry() {
        return country;
    }

    public String getTradingCurrency() {
        return tradingCurrency;
    }

    public TenantStatus getStatus() {
        return status;
    }
}
