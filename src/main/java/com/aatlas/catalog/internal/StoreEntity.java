package com.aatlas.catalog.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** A branch. See {@code V7} for what each column means, and {@code V17} for {@code source}. */
@Entity
@Table(name = "stores")
public class StoreEntity extends TenantScopedEntity {

    /**
     * The branch as people and the ERP name it, and the key everything off this table
     * joins by as text - {@code sales_transactions.branch_code} and
     * {@code products.default_store_code} both hold it with no foreign key behind them.
     * Not updatable for exactly that reason: renaming it here would silently orphan them.
     */
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

    /**
     * Where the branch came from, so the connect screen can tell "this arrived from your
     * import and needs a region" apart from "someone created this and left it unassigned
     * on purpose". Both are unassigned; only one is a prompt.
     */
    @Column(name = "source", nullable = false, updatable = false)
    private String source = Source.MANUAL;

    /** The four values {@code stores_source_ck} admits. Lowercase, so no enum mapping. */
    static final class Source {
        static final String MANUAL = "manual";
        static final String IMPORT = "import";
        static final String ERP = "erp";
        static final String SAMPLE = "sample";

        private Source() {
        }
    }

    /** The region a branch sits in until a human places it. See {@code V17}. */
    static final String UNASSIGNED_REGION = "unassigned";

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
            String mapAnchor,
            String source) {
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
        this.source = source;
        this.active = true;
    }

    /**
     * Applies an edit that has already been validated and defaulted.
     *
     * <p>Takes every field rather than the ones that changed: the caller has resolved
     * "absent means leave it" against the current row before it gets here, so this method
     * has one behaviour instead of a null check per column. {@code storeCode} and
     * {@code source} are not arguments because neither may change.
     */
    void apply(
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
            String mapAnchor,
            boolean active) {
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
        this.active = active;
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

    /** Opening a branch that is not trading yet, and retiring one that no longer is. */
    void setActive(boolean active) {
        this.active = active;
    }

    public String getSource() {
        return source;
    }
}
