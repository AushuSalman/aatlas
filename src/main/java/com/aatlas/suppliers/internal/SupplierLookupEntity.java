package com.aatlas.suppliers.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The result of one "pull their information from the web" call, kept so that adding to the
 * panel references a result the user has already seen rather than a second computation that
 * might differ.
 *
 * <p>{@code profile} and {@code sources} are stored as raw JSON text in {@code jsonb}
 * columns (native Hibernate 6 {@code @JdbcTypeCode(SqlTypes.JSON)}, not an object mapper on
 * the entity) and (de)serialised by the service, so this class stays a plain record of what
 * was shown rather than owning the wire shape.
 */
@Entity
@Table(name = "supplier_lookups")
class SupplierLookupEntity extends TenantScopedEntity {

    @Column(name = "query", nullable = false)
    private String query;

    @Column(name = "country", nullable = false)
    private String country;

    @Column(name = "supplier_key", nullable = false)
    private String supplierKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "profile", columnDefinition = "jsonb", nullable = false)
    private String profileJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources", columnDefinition = "jsonb", nullable = false)
    private String sourcesJson;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "watch_outs", nullable = false)
    private List<String> watchOuts;

    @Column(name = "recommendation", nullable = false)
    private String recommendation;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_by")
    private UUID createdBy;

    protected SupplierLookupEntity() {
        // JPA
    }

    SupplierLookupEntity(String query, String country, String supplierKey, String profileJson,
            String sourcesJson, List<String> watchOuts, String recommendation, String status, UUID createdBy) {
        this.query = query;
        this.country = country;
        this.supplierKey = supplierKey;
        this.profileJson = profileJson;
        this.sourcesJson = sourcesJson;
        this.watchOuts = watchOuts;
        this.recommendation = recommendation;
        this.status = status;
        this.createdBy = createdBy;
    }

    String getQuery() {
        return query;
    }

    String getCountry() {
        return country;
    }

    String getSupplierKey() {
        return supplierKey;
    }

    String getProfileJson() {
        return profileJson;
    }

    String getSourcesJson() {
        return sourcesJson;
    }

    List<String> getWatchOuts() {
        return watchOuts;
    }

    String getRecommendation() {
        return recommendation;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }
}
