package com.aatlas.analytics.internal.ledger;

/** Branch and category filters, {@code "all"} meaning no filter. Ports {@code procurement.ts}'s {@code Filters}/{@code ALL}. */
public record Filters(String branch, String category) {

    public static final String ALL = "all";

    public static Filters none() {
        return new Filters(ALL, ALL);
    }
}
