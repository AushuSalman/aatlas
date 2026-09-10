package com.aatlas.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A seat: the closed set of eight keys a user can hold.
 *
 * <p>The wire values are kebab-case and match the frontend's {@code Role} union in
 * {@code src/lib/platform/types.ts} character for character. They are also the values
 * stored in {@code users.seat_role} and pinned by {@code users_seat_role_ck}, so renaming
 * one is a migration and a frontend change together - which is the point. A seat name
 * that drifts between the two halves is a permission bug waiting to happen.
 *
 * <p>Deliberately nothing else. What a seat may open, whether it works in bulk, whether it
 * may move the guardrails and how much it may approve alone are rows in
 * {@code role_policy}, read at request time through {@code PolicyReader} in the
 * {@code policy} module - so a tenant can change a limit without a deploy, and so there
 * is exactly one place that answers "may this seat do that".
 */
public enum SeatRole {

    SALES_REP("sales-rep"),
    SELLER("seller"),
    SALES_HEAD("sales-head"),
    PURCHASE_MANAGER("purchase-manager"),
    BUYER("buyer"),
    PURCHASE_HEAD("purchase-head"),
    FINANCE("finance"),
    BOTH("both");

    private static final Map<String, SeatRole> BY_WIRE_VALUE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(SeatRole::wireValue, Function.identity()));

    private final String wireValue;

    SeatRole(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /**
     * The Spring Security authority this seat maps to, e.g. {@code ROLE_sales-rep}.
     *
     * <p>Built from the wire value because that is what {@code SecurityConfig}'s
     * {@code JwtAuthenticationConverter} already prefixes with {@code ROLE_} when it reads
     * the {@code role} claim. Two spellings of the same authority would mean
     * {@code @PreAuthorize} silently matching nothing.
     */
    public String authority() {
        return "ROLE_" + wireValue;
    }

    /**
     * Parses a wire value. Unknown seats fail rather than defaulting: quietly falling back
     * to a role would be an authorisation decision made by a typo.
     */
    @JsonCreator
    public static SeatRole from(String value) {
        SeatRole role = value == null ? null : BY_WIRE_VALUE.get(value.strip().toLowerCase(Locale.ROOT));
        if (role == null) {
            throw new IllegalArgumentException(
                    "Unknown role '" + value + "'. Expected one of " + BY_WIRE_VALUE.keySet());
        }
        return role;
    }

    /**
     * Persists the wire value, not {@link Enum#name()}, so the column matches the JSON and
     * a human reading the table sees what the frontend sent.
     */
    @Converter(autoApply = true)
    public static class JpaConverter implements AttributeConverter<SeatRole, String> {

        @Override
        public String convertToDatabaseColumn(SeatRole role) {
            return role == null ? null : role.wireValue();
        }

        @Override
        public SeatRole convertToEntityAttribute(String value) {
            return value == null ? null : from(value);
        }
    }
}
