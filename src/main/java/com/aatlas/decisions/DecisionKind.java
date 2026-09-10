package com.aatlas.decisions;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Wire values match the frontend's {@code DecisionKind} union exactly: hyphenated, lowercase. */
public enum DecisionKind {
    SELL("sell"),
    BUY("buy"),
    BULK_SELL("bulk-sell"),
    BULK_BUY("bulk-buy");

    private final String wire;

    DecisionKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static DecisionKind fromWire(String value) {
        for (DecisionKind k : values()) {
            if (k.wire.equals(value)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Unknown decision kind: " + value);
    }
}
