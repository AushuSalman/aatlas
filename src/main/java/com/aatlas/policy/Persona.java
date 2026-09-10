package com.aatlas.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * What a seat may do: which workspaces open, whether it works one line at a time or whole
 * baskets, whether it may move the margin guardrails, and how much it may commit alone.
 *
 * <p>Field for field the frontend's {@code Persona} in {@code src/lib/platform/session.ts},
 * so {@code GET /me} and {@code GET /roles} render without mapping. {@code approveLimit}
 * and {@code approver} are absent (not null) when a seat has no ceiling, which is how the
 * TypeScript type spells "no limit".
 *
 * <p>Read from {@code role_policy} at request time through {@link PolicyReader}; the
 * {@code SeatRole} enum in {@code identity} is only the closed set of keys.
 *
 * @param key the seat's wire value, e.g. {@code purchase-head}
 * @param title the job title shown beside the name
 * @param side which half of the business the seat lives on
 * @param level seniority, which is what bulk actions and guardrail edits key off
 * @param modules the workspaces the rail shows and the route guard admits
 * @param bulk bulk repricing and basket awards
 * @param guardrails may change the margin guardrails
 * @param approveLimit largest single order committed alone, in USD; null means no limit
 * @param approver who signs off above the limit, as a title; null when there is no limit
 * @param blurb one line for the seat picker
 */
@Schema(name = "Persona", description = "What a seat may open, bulk, change and approve.")
public record Persona(
        String key,
        String title,
        Side side,
        Level level,
        List<String> modules,
        boolean bulk,
        boolean guardrails,
        BigDecimal approveLimit,
        String approver,
        String blurb) {

    /** Which half of the business a seat lives on. Decides what the home screen shows. */
    public enum Side {
        SELL,
        BUY,
        BOTH,
        NONE;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Side from(String value) {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
    }

    /** Seniority. Heads and the director set policy; managers work in bulk; reps quote. */
    public enum Level {
        REP,
        MANAGER,
        HEAD,
        EXEC;

        @JsonValue
        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Level from(String value) {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
    }

    public Persona {
        modules = List.copyOf(modules);
    }

    /** Whether this seat may commit an order of this size without a signature from above. */
    public boolean canApprove(BigDecimal orderValueUsd) {
        return approveLimit == null || orderValueUsd.compareTo(approveLimit) <= 0;
    }

    /**
     * Heads of either side and the commercial director: the seats that may rename the
     * company. Not part of the wire contract - the frontend's {@code Persona} type has no
     * such field - so it is excluded from serialisation; Jackson would otherwise pick up
     * this {@code isXxx()} accessor as a property alongside the record's own components.
     */
    @JsonIgnore
    public boolean isHeadOrDirector() {
        return level == Level.HEAD || "both".equals(key);
    }
}
