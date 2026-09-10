package com.aatlas.catalog.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** A branch. See {@code V7} for what each column means. */
@Entity
@Table(name = "stores")
public class StoreEntity extends TenantScopedEntity {

    @Column(name = "store_code", nullable = false, updatable = false)
    private String storeCode;

    @Column(name = "company_number")
    private String companyNumber;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @Column(name = "subdivision_code")
    private String subdivisionCode;

    @Column(name = "msa_name")
    private String msaName;

    @Column(name = "rpp", precision = 6, scale = 1)
    private BigDecimal rpp;

    @Column(name = "txns")
    private Integer txns;

    @Column(name = "item_count")
    private Integer itemCount;

    @Column(name = "segment")
    private String segment;

    @Column(name = "region_key", nullable = false)
    private String regionKey;

    @Column(name = "map_x")
    private Integer mapX;

    @Column(name = "map_y")
    private Integer mapY;

    @Column(name = "map_anchor")
    private String mapAnchor;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected StoreEntity() {
        // JPA
    }

    StoreEntity(
            UUID tenantId,
            String storeCode,
            String companyNumber,
            String legalName,
            String country,
            String subdivisionCode,
            String msaName,
            BigDecimal rpp,
            Integer txns,
            Integer itemCount,
            String segment,
            String regionKey,
            Integer mapX,
            Integer mapY,
            String mapAnchor) {
        setTenantId(tenantId);
        this.storeCode = storeCode;
        this.companyNumber = companyNumber;
        this.legalName = legalName;
        this.country = country;
        this.subdivisionCode = subdivisionCode;
        this.msaName = msaName;
        this.rpp = rpp;
        this.txns = txns;
        this.itemCount = itemCount;
        this.segment = segment;
        this.regionKey = regionKey;
        this.mapX = mapX;
        this.mapY = mapY;
        this.mapAnchor = mapAnchor;
        this.active = true;
    }

    public String getStoreCode() {
        return storeCode;
    }

    public String getCompanyNumber() {
        return companyNumber;
    }

    public String getLegalName() {
        return legalName;
    }

    public String getCountry() {
        return country;
    }

    public String getSubdivisionCode() {
        return subdivisionCode;
    }

    public String getMsaName() {
        return msaName;
    }

    public BigDecimal getRpp() {
        return rpp;
    }

    public Integer getTxns() {
        return txns;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public String getSegment() {
        return segment;
    }

    public String getRegionKey() {
        return regionKey;
    }

    public Integer getMapX() {
        return mapX;
    }

    public Integer getMapY() {
        return mapY;
    }

    public String getMapAnchor() {
        return mapAnchor;
    }

    public boolean isActive() {
        return active;
    }
}
