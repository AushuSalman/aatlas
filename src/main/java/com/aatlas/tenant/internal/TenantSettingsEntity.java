package com.aatlas.tenant.internal;

import com.aatlas.tenant.CountryCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Where a tenant trades and in what. The source of truth since V5; {@link TenantEntity}
 * carries a copy that {@code TenantService} keeps in step.
 *
 * <p>Keyed by the tenant: one row each, created by signup, never deleted on its own.
 * {@code version} is a wrapper so Spring Data persists a new row rather than merging it.
 */
@Entity
@Table(name = "tenant_settings")
@EntityListeners(AuditingEntityListener.class)
public class TenantSettingsEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "country_code", nullable = false, length = 2)
    private CountryCode country;

    @Column(name = "trading_currency", nullable = false, length = 3)
    private String tradingCurrency;

    /** Null while the row still holds what signup chose. */
    @Column(name = "updated_by")
    private UUID updatedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected TenantSettingsEntity() {
        // JPA
    }

    TenantSettingsEntity(UUID tenantId, CountryCode country, String tradingCurrency) {
        this.tenantId = tenantId;
        this.country = country;
        this.tradingCurrency = tradingCurrency;
    }

    void change(CountryCode country, String tradingCurrency, UUID updatedBy) {
        this.country = country;
        this.tradingCurrency = tradingCurrency;
        this.updatedBy = updatedBy;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public CountryCode getCountry() {
        return country;
    }

    public String getTradingCurrency() {
        return tradingCurrency;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
