package com.aatlas.demandmodel.internal;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * A tenant's trained demand model: the serialised forest, the per-pair report and context,
 * and what the run was fitted on. One row per tenant, keyed by the tenant. A row with a null
 * {@code model} is a tenant the trainer visited and could not fit (too little history); its
 * {@code note} says so.
 */
@Entity
@Table(name = "demand_models")
public class DemandModelEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** The forecast model (sees the lags) as its protobuf bytes; null until a fit succeeded. */
    @Column(name = "model")
    private byte[] model;

    /** The response model (no lags; price sensitivity and settled demand), the same way. */
    @Column(name = "response_model")
    private byte[] responseModel;

    @Type(JsonType.class)
    @Column(name = "pairs", nullable = false, columnDefinition = "jsonb")
    private Map<String, PairRecord> pairs = new LinkedHashMap<>();

    @Column(name = "trained_at")
    private Instant trainedAt;

    @Column(name = "row_count", nullable = false)
    private int rows;

    @Column(name = "pair_count", nullable = false)
    private int pairCount;

    @Column(name = "weeks", nullable = false)
    private int weeks;

    @Column(name = "from_week")
    private LocalDate fromWeek;

    @Column(name = "to_week")
    private LocalDate toWeek;

    @Column(name = "holdout_weeks", nullable = false)
    private int holdoutWeeks;

    @Column(name = "train_millis", nullable = false)
    private long trainMillis;

    @Column(name = "note")
    private String note;

    @Version
    @Column(name = "version")
    private Long version;

    protected DemandModelEntity() {
    }

    static DemandModelEntity fresh(UUID tenantId) {
        DemandModelEntity e = new DemandModelEntity();
        e.tenantId = tenantId;
        e.pairs = new LinkedHashMap<>();
        return e;
    }

    /** A visit that could not fit: the model, if any, is dropped so a stale one never serves. */
    void untrained(String why, Instant at, int rows, LocalDate from, LocalDate to, int holdoutWeeks) {
        this.model = null;
        this.responseModel = null;
        this.pairs = new LinkedHashMap<>();
        this.trainedAt = at;
        this.rows = rows;
        this.pairCount = 0;
        this.weeks = 0;
        this.fromWeek = from;
        this.toWeek = to;
        this.holdoutWeeks = holdoutWeeks;
        this.trainMillis = 0;
        this.note = why;
    }

    void trained(byte[] bytes, byte[] responseBytes, Map<String, PairRecord> pairs, Instant at, int rows, int weeks,
            LocalDate from, LocalDate to, int holdoutWeeks, long millis, String note) {
        this.model = bytes;
        this.responseModel = responseBytes;
        this.pairs = new LinkedHashMap<>(pairs);
        this.trainedAt = at;
        this.rows = rows;
        this.pairCount = pairs.size();
        this.weeks = weeks;
        this.fromWeek = from;
        this.toWeek = to;
        this.holdoutWeeks = holdoutWeeks;
        this.trainMillis = millis;
        this.note = note;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public byte[] getModel() {
        return model;
    }

    public byte[] getResponseModel() {
        return responseModel;
    }

    public Map<String, PairRecord> getPairs() {
        return pairs;
    }

    public Instant getTrainedAt() {
        return trainedAt;
    }

    public int getRows() {
        return rows;
    }

    public int getPairCount() {
        return pairCount;
    }

    public int getWeeks() {
        return weeks;
    }

    public LocalDate getFromWeek() {
        return fromWeek;
    }

    public LocalDate getToWeek() {
        return toWeek;
    }

    public int getHoldoutWeeks() {
        return holdoutWeeks;
    }

    public long getTrainMillis() {
        return trainMillis;
    }

    public String getNote() {
        return note;
    }

    public Long getVersion() {
        return version;
    }
}
