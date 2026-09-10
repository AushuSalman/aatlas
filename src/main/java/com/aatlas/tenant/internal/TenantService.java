package com.aatlas.tenant.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.policy.PolicyReader;
import com.aatlas.tenant.CountryCode;
import com.aatlas.tenant.TenantDirectory;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The company profile and its settings.
 *
 * <p>{@code tenant_settings} is the source of truth for country and currency; this
 * service is the only writer of it and rewrites the copy on {@code tenants} in the same
 * transaction, so the two can only disagree inside a transaction that then rolls back.
 *
 * <p>Renaming is for heads and the director, read from {@code role_policy} through
 * {@link PolicyReader}. Settings are open to any seat, as they are on the frontend's
 * Settings screen today.
 */
@Service
class TenantService implements TenantDirectory {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenants;
    private final TenantSettingsRepository settings;
    private final ReferenceData reference;
    private final PolicyReader policy;

    TenantService(
            TenantRepository tenants, TenantSettingsRepository settings, ReferenceData reference, PolicyReader policy) {
        this.tenants = tenants;
        this.settings = settings;
        this.reference = reference;
        this.policy = policy;
    }

    @Override
    @Transactional(readOnly = true)
    public TenantInfo get(UUID tenantId) {
        TenantEntity tenant = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant", tenantId));
        TenantSettingsEntity row = settingsRow(tenantId);
        return new TenantInfo(tenant.getId(), tenant.getName(), tenant.getSlug(), tenant.getStatus().name(),
                row.getCountry(), row.getTradingCurrency(), tenant.getCreatedAt());
    }

    @Transactional
    TenantInfo rename(UUID tenantId, TenantContext.Actor actor, String name) {
        if (!policy.personaFor(tenantId, actor.role()).isHeadOrDirector()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed",
                    "Only heads of sales and purchasing and the commercial director can rename the company.");
        }
        TenantEntity tenant = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant", tenantId));
        tenant.rename(TenantProvisioningService.cleanName(name));
        tenants.saveAndFlush(tenant);
        log.info("Tenant {} renamed by user {}", tenantId, actor.userId());
        return get(tenantId);
    }

    @Cacheable(cacheNames = CacheNames.TENANT_SETTINGS, key = "#tenantId")
    @Transactional(readOnly = true)
    TenantSettingsView settings(UUID tenantId) {
        return view(settingsRow(tenantId));
    }

    /**
     * Changes country and/or currency.
     *
     * <p>Same rule as the frontend's {@code setLocale}: a new country brings its own
     * currency unless the caller names one; a currency alone leaves the country as it is.
     */
    @CacheEvict(cacheNames = CacheNames.TENANT_SETTINGS, key = "#tenantId")
    @Transactional
    TenantSettingsView updateSettings(UUID tenantId, TenantContext.Actor actor, CountryCode country, String currency) {
        if (country == null && (currency == null || currency.isBlank())) {
            throw ApiException.badRequest("nothing_to_change", "Send a country, a currency, or both.");
        }
        TenantSettingsEntity row = settingsRow(tenantId);
        CountryCode nextCountry = country == null ? row.getCountry() : country;

        String nextCurrency;
        if (currency != null && !currency.isBlank()) {
            nextCurrency = currency.strip().toUpperCase(Locale.ROOT);
            if (!reference.isCurrency(nextCurrency)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "That currency is not supported.",
                        java.util.Map.of("fields", java.util.Map.of("currency",
                                "Choose one of " + String.join(", ", reference.currencyCodes()) + ".")));
            }
        } else if (nextCountry != row.getCountry()) {
            nextCurrency = reference.country(nextCountry).currency();
        } else {
            nextCurrency = row.getTradingCurrency();
        }

        row.change(nextCountry, nextCurrency, actor.userId());
        settings.saveAndFlush(row);

        // The copy on tenants follows in the same transaction; nothing else writes it.
        TenantEntity tenant = tenants.findById(tenantId).orElseThrow(() -> ApiException.notFound("Tenant", tenantId));
        tenant.mirror(row);
        tenants.saveAndFlush(tenant);

        log.info("Tenant {} settings: country={} currency={} by user {}", tenantId, nextCountry, nextCurrency,
                actor.userId());
        return view(row);
    }

    private TenantSettingsEntity settingsRow(UUID tenantId) {
        // V5 backfilled a row for every tenant and provisioning writes one for each new
        // company, so a miss means the schema and the code disagree, not that a tenant
        // simply has not chosen yet.
        return settings.findById(tenantId)
                .orElseThrow(() -> new IllegalStateException("tenant_settings has no row for tenant " + tenantId));
    }

    private TenantSettingsView view(TenantSettingsEntity row) {
        ReferenceData.Country country = reference.country(row.getCountry());
        return new TenantSettingsView(row.getCountry(), row.getTradingCurrency(), country.subdivisionNoun(),
                country.subdivisionNounPlural(), country.regionNoun());
    }
}
