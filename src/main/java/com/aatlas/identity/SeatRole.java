package com.aatlas.identity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A seat: which workspaces open, and how much this person may commit alone.
 *
 * <p>The wire values are kebab-case and match the frontend's {@code Role} union in
 * {@code src/lib/platform/types.ts} character for character. They are also the values
 * stored in {@code users.seat_role} and pinned by {@code users_seat_role_ck}, so renaming
 * one is a migration and a frontend change together - which is the point. A seat name
 * that drifts between the two halves is a permission bug waiting to happen.
 *
 * <p>The approval limit lives here rather than in a config file because it is the same
 * number the buy side checks before committing an order, and a limit nobody can find is a
 * limit nobody reviews. The blueprint moves these to a {@code role_policy} table when
 * customers need their own; until then the personas are the product's, not the tenant's.
 */
public enum SeatRole {

    SALES_REP("sales-rep", "Sales rep", Side.SELL, Level.REP, false, false, null),
    SELLER("seller", "Sales manager", Side.SELL, Level.MANAGER, true, false, null),
    SALES_HEAD("sales-head", "Head of sales", Side.SELL, Level.HEAD, true, true, null),
    PURCHASE_MANAGER(
            "purchase-manager", "Purchase manager", Side.BUY, Level.MANAGER, false, false, new BigDecimal("50000")),
    BUYER("buyer", "Category buyer", Side.BUY, Level.MANAGER, true, false, new BigDecimal("150000")),
    PURCHASE_HEAD("purchase-head", "Head of purchasing", Side.BUY, Level.HEAD, true, true, null),
    FINANCE("finance", "Finance", Side.NONE, Level.EXEC, false, true, null),
    BOTH("both", "Commercial director", Side.BOTH, Level.EXEC, true, true, null);

    /** Which half of the business a seat lives on. Decides what the home screen shows. */
    public enum Side {
        SELL,
        BUY,
        BOTH,
        NONE
    }

    /** Seniority, which is what bulk actions and guardrail edits key off. */
    public enum Level {
        REP,
        MANAGER,
        HEAD,
        EXEC
    }

    private static final Map<String, SeatRole> BY_WIRE_VALUE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(SeatRole::wireValue, Function.identity()));

    private final String wireValue;
    private final String title;
    private final Side side;
    private final Level level;
    private final boolean bulk;
    private final boolean guardrails;
    private final BigDecimal approvalLimit;

    SeatRole(
            String wireValue,
            String title,
            Side side,
            Level level,
            boolean bulk,
            boolean guardrails,
            BigDecimal approvalLimit) {
        this.wireValue = wireValue;
        this.title = title;
        this.side = side;
        this.level = level;
        this.bulk = bulk;
        this.guardrails = guardrails;
        this.approvalLimit = approvalLimit;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** The job title shown beside the name. Signup takes it from here, never from input. */
    public String title() {
        return title;
    }

    public Side side() {
        return side;
    }

    public Level level() {
        return level;
    }

    /** Bulk repricing and basket awards: managers and above. */
    public boolean canBulk() {
        return bulk;
    }

    /** Change the margin guardrails the team prices inside: heads, finance, the director. */
    public boolean canEditGuardrails() {
        return guardrails;
    }

    /** Largest single order this seat commits alone. Empty means no ceiling. */
    public Optional<BigDecimal> approvalLimit() {
        return Optional.ofNullable(approvalLimit);
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
