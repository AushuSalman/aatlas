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

    /**
     * A copy of {@code tenant_settings.country_code}. Since V5 the settings row is the
     * source of truth; this and {@link #tradingCurrency} are rewritten by
     * {@code TenantService} in the same transaction as that row, and by nothing else.
     * Kept because signup's session and every report already read them from here.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "country", nullable = false, length = 2)
    private CountryCode country;

    /** Copy of {@code tenant_settings.trading_currency}; see {@link #country}. */
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

    void rename(String name) {
        this.name = name;
    }

    /** Keeps the denormalised copy in step with {@code tenant_settings}. */
    void mirror(TenantSettingsEntity settings) {
        this.country = settings.getCountry();
        this.tradingCurrency = settings.getTradingCurrency();
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
