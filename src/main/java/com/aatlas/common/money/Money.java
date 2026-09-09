package com.aatlas.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Money is {@code numeric(14,4)} in the database and {@link BigDecimal} in Java. Never a
 * {@code double}: the golden-file tests compare the Java engine to the TypeScript
 * prototype to the cent, and binary floating point cannot hold that line.
 *
 * <p>Amounts are stored in USD with the original currency recorded beside them, so a
 * tenant trading in GBP still rolls up correctly. Conversion is a separate concern
 * ({@code fx_rate}), deliberately not hidden inside this type.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public static final int SCALE = 4;
    public static final String USD = "USD";

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
        currency = currency.toUpperCase(java.util.Locale.ROOT);
    }

    public static Money usd(BigDecimal amount) {
        return new Money(amount, USD);
    }

    public static Money usd(double amount) {
        return new Money(BigDecimal.valueOf(amount), USD);
    }

    public static Money of(BigDecimal amount, String currency) {
        return new Money(amount, currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money plus(Money other) {
        return new Money(amount.add(sameCurrency(other).amount), currency);
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(sameCurrency(other).amount), currency);
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money times(long factor) {
        return times(BigDecimal.valueOf(factor));
    }

    /** The price after a percentage move, e.g. {@code applyPct(new BigDecimal("4.5"))}. */
    public Money applyPct(BigDecimal percent) {
        BigDecimal multiplier = BigDecimal.ONE.add(percent.movePointLeft(2));
        return times(multiplier);
    }

    /** What the screen shows: two decimals, half-up. */
    public BigDecimal display() {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(sameCurrency(other).amount);
    }

    private Money sameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Cannot combine %s and %s without an fx_rate".formatted(currency, other.currency));
        }
        return other;
    }

    @Override
    public String toString() {
        return display() + " " + currency;
    }
}
