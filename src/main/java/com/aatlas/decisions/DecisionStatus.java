package com.aatlas.decisions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Not part of the frontend's browser-only {@code Decision} (every recorded decision there is
 * implicitly applied) - added so the {@code approvals} module has somewhere to move a decision
 * that needs sign-off before {@code applied} lets a purchase order land in the ledger.
 */
public enum DecisionStatus {
    APPLIED("applied"),
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected");

    private final String wire;

    DecisionStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static DecisionStatus fromWire(String value) {
        for (DecisionStatus s : values()) {
            if (s.wire.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown decision status: " + value);
    }
}
