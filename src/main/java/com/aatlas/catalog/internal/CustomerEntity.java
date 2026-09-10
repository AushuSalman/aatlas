package com.aatlas.catalog.internal;

import com.aatlas.common.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** An account a rep quotes. See {@code V7} for what each column means. */
@Entity
@Table(name = "customers")
public class CustomerEntity extends TenantScopedEntity {

    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "segment", nullable = false)
    private String segment;

    @Column(name = "tier", nullable = false, length = 1)
    private String tier;

    @Column(name = "agreed_discount_pct", nullable = false, precision = 6, scale = 2)
    private BigDecimal agreedDiscountPct;

    @Column(name = "typical_qty", nullable = false)
    private int typicalQty;

    @Column(name = "profile", nullable = false)
    private String profile;

    @Column(name = "sla_days", nullable = false)
    private int slaDays;

    @Column(name = "note")
    private String note;

    protected CustomerEntity() {
        // JPA
    }

    CustomerEntity(
            UUID tenantId,
            String code,
            String name,
            String segment,
            String tier,
            BigDecimal agreedDiscountPct,
            int typicalQty,
            String profile,
            int slaDays,
            String note) {
        setTenantId(tenantId);
        this.code = code;
        this.name = name;
        this.segment = segment;
        this.tier = tier;
        this.agreedDiscountPct = agreedDiscountPct;
        this.typicalQty = typicalQty;
        this.profile = profile;
        this.slaDays = slaDays;
        this.note = note;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSegment() {
        return segment;
    }

    public String getTier() {
        return tier;
    }

    public BigDecimal getAgreedDiscountPct() {
        return agreedDiscountPct;
    }

    public int getTypicalQty() {
        return typicalQty;
    }

    public String getProfile() {
        return profile;
    }

    public int getSlaDays() {
        return slaDays;
    }

    public String getNote() {
        return note;
    }
}
