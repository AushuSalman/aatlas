package com.aatlas.pricingmodel.internal;

import com.aatlas.history.PricingModel;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Type;

/**
 * A tenant's saved pricing-model overrides, and what the model learned for itself. Keyed
 * by the tenant, because there is exactly one row per tenant and the row's identity
 * <em>is</em> the tenant.
 *
 * <p>{@code settings} is the hand-set override map exactly as
 * {@link PricingModel.Config#overrides()} gives it: only what differs from the registry
 * defaults, keyed by parameter key. Absent for a tenant that has never saved, and {@code {}}
 * after a reset; both mean the defaults. {@code learned} is the same shape, written by the
 * model tuner rather than a person, with a {@link LearnedNote} per key in
 * {@code learnedNotes}; the chain runs on defaults, then learned, then settings
 * ({@link PricingModel.Config#layered}). Both sides of the model share each map.
 *
 * <p>{@code updatedAt} and {@code updatedBy} move on a hand save or reset only; a retune
 * moves {@code learnedAt} alone, so "last changed by" on the settings screen stays true.
 * The timestamps are set from the caller's clock rather than by JPA auditing for that
 * reason. {@code version} is a wrapper so Spring Data can tell a new row (null) from a
 * loaded one and persist rather than merge.
 */
@Entity
@Table(name = "pricing_model_settings")
public class PricingModelEntity {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Type(JsonType.class)
    @Column(name = "settings", nullable = false, columnDefinition = "jsonb")
    private Map<String, PricingModel.Setting> settings;

    @Type(JsonType.class)
    @Column(name = "learned", nullable = false, columnDefinition = "jsonb")
    private Map<String, PricingModel.Setting> learned;

    @Type(JsonType.class)
    @Column(name = "learned_notes", nullable = false, columnDefinition = "jsonb")
    private Map<String, LearnedNote> learnedNotes;

    @Column(name = "learned_at")
    private Instant learnedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PricingModelEntity() {
        // JPA
    }

    /**
     * A new row. {@code updatedBy} is null when the tuner creates it ahead of any hand
     * save; {@code at} is both created and updated, since the column is not nullable.
     */
    PricingModelEntity(UUID tenantId, Map<String, PricingModel.Setting> settings, UUID updatedBy, Instant at) {
        this.tenantId = tenantId;
        this.createdAt = at;
        this.learned = new LinkedHashMap<>();
        this.learnedNotes = new LinkedHashMap<>();
        apply(settings, updatedBy, at);
    }

    /** A hand save or reset: replaces the whole override map and moves the row's stamp. */
    void apply(Map<String, PricingModel.Setting> settings, UUID updatedBy, Instant at) {
        this.settings = settings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(settings);
        this.updatedBy = updatedBy;
        this.updatedAt = at;
    }

    /**
     * A retune of one side: that side's learned keys become exactly {@code sideLearned}
     * (so a key whose evidence gate no longer passes is dropped), the other side's are
     * kept, and {@code learnedAt} moves. The hand-set map and its stamp are untouched.
     */
    void learn(PricingModel.Side side, Map<String, PricingModel.Setting> sideLearned,
            Map<String, LearnedNote> sideNotes, Instant at) {
        Map<String, PricingModel.Setting> nextLearned = otherSide(getLearned(), side);
        Map<String, LearnedNote> nextNotes = otherSide(getLearnedNotes(), side);
        if (sideLearned != null) {
            nextLearned.putAll(sideLearned);
        }
        if (sideNotes != null) {
            nextNotes.putAll(sideNotes);
        }
        this.learned = nextLearned;
        this.learnedNotes = nextNotes;
        this.learnedAt = at;
    }

    /** Wipes one side's learned keys and notes; the other side and {@code learnedAt} stay. */
    void clearLearned(PricingModel.Side side) {
        this.learned = otherSide(getLearned(), side);
        this.learnedNotes = otherSide(getLearnedNotes(), side);
    }

    /** The entries of {@code all} whose key belongs to the other side; an unknown key is dropped. */
    static <T> Map<String, T> otherSide(Map<String, T> all, PricingModel.Side side) {
        Map<String, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, T> e : all.entrySet()) {
            PricingModel.Parameter p = PricingModel.parameter(e.getKey()).orElse(null);
            if (p != null && p.side() != side) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** The entries of {@code all} whose key belongs to {@code side}, in the order stored; an unknown key is dropped. */
    static <T> Map<String, T> sideOf(Map<String, T> all, PricingModel.Side side) {
        Map<String, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, T> e : all.entrySet()) {
            PricingModel.Parameter p = PricingModel.parameter(e.getKey()).orElse(null);
            if (p != null && p.side() == side) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /** The stored hand-set overrides; never null. Normalise through {@link PricingModel.Config#of} before reading. */
    Map<String, PricingModel.Setting> getSettings() {
        return settings == null ? Map.of() : settings;
    }

    /** What the tuner learned, both sides; never null. */
    Map<String, PricingModel.Setting> getLearned() {
        return learned == null ? Map.of() : learned;
    }

    /** The reason and evidence behind each learned key, both sides; never null. */
    Map<String, LearnedNote> getLearnedNotes() {
        return learnedNotes == null ? Map.of() : learnedNotes;
    }

    /** When the tuner last ran for this tenant; null until it has. */
    public Instant getLearnedAt() {
        return learnedAt;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
