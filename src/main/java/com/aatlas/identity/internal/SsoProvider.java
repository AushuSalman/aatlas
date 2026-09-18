package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import java.util.Locale;

/** The identity providers a person may sign in with. Wire values match the frontend's {@code SsoProvider}. */
enum SsoProvider {
    GOOGLE("google", "Google"),
    APPLE("apple", "Apple");

    private final String wireValue;
    private final String label;

    SsoProvider(String wireValue, String label) {
        this.wireValue = wireValue;
        this.label = label;
    }

    String wireValue() {
        return wireValue;
    }

    String label() {
        return label;
    }

    static SsoProvider fromWire(String value) {
        String v = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (SsoProvider p : values()) {
            if (p.wireValue.equals(v)) {
                return p;
            }
        }
        throw ApiException.badRequest("unknown_provider", "Sign-in with that provider is not supported.");
    }
}
