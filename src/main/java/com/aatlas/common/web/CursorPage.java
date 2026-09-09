package com.aatlas.common.web;

import java.util.List;
import java.util.function.Function;

/**
 * Keyset pagination, which is what the ledger and history endpoints use.
 *
 * <p>Offset pagination degrades as the offset grows and skips or repeats rows when
 * writes land mid-scroll. A cursor encoding the last row's sort key is stable and costs
 * the same on page 1 and page 900 — which matters for a 26-month purchase-order ledger.
 *
 * @param items    this page, already in sort order
 * @param nextCursor opaque cursor for the following page, or {@code null} at the end
 * @param limit    how many were asked for
 */
public record CursorPage<T>(List<T> items, String nextCursor, int limit) {

    public static <T> CursorPage<T> of(List<T> items, int limit, Function<T, String> cursorOf) {
        boolean hasMore = items.size() > limit;
        List<T> page = hasMore ? items.subList(0, limit) : items;
        String next = hasMore ? cursorOf.apply(page.get(page.size() - 1)) : null;
        return new CursorPage<>(List.copyOf(page), next, limit);
    }

    public static <T> CursorPage<T> empty(int limit) {
        return new CursorPage<>(List.of(), null, limit);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }

    public <R> CursorPage<R> map(Function<T, R> mapper) {
        return new CursorPage<>(items.stream().map(mapper).toList(), nextCursor, limit);
    }
}
