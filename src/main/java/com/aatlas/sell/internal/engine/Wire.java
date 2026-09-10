package com.aatlas.sell.internal.engine;

import java.math.BigDecimal;
import java.util.OptionalDouble;

/** {@code double} to {@code BigDecimal} at the boundary between engine math and a DTO. */
final class Wire {

    private Wire() {
    }

    static BigDecimal bd(double n) {
        return BigDecimal.valueOf(n);
    }

    static BigDecimal bdOrNull(OptionalDouble n) {
        return n.isPresent() ? BigDecimal.valueOf(n.getAsDouble()) : null;
    }
}
