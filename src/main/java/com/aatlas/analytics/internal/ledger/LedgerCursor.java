package com.aatlas.analytics.internal.ledger;

import com.aatlas.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

/**
 * Opaque keyset cursors for the ledger listing: the {@code (order_date, seq)} of the last row
 * on a page, base64url-encoded. The ledger's natural order is {@code order_date DESC, seq ASC}
 * (see V12's index and {@link PoRow#seq}), so a page boundary is exactly that pair.
 */
record LedgerCursor(LocalDate date, int seq) {

    String encode() {
        String raw = date + "|" + seq;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static LedgerCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            return new LedgerCursor(LocalDate.parse(parts[0]), Integer.parseInt(parts[1]));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("invalid_cursor", "That cursor is not one this API issued. Start again without a cursor.");
        }
    }
}
