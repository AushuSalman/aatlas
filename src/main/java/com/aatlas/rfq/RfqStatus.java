package com.aatlas.rfq;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Wire values match the blueprint's {@code rfq.status} column exactly. */
public enum RfqStatus {
    DRAFT("draft"),
    SENT("sent"),
    QUOTED("quoted"),
    AWARDED("awarded"),
    CLOSED("closed");

    private final String wire;

    RfqStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RfqStatus fromWire(String value) {
        for (RfqStatus s : values()) {
            if (s.wire.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown RFQ status: " + value);
    }
}
