package com.aatlas.catalog.internal;

import com.aatlas.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Opaque keyset cursors for the catalogue lists.
 *
 * <p>The sort key of the last row on a page (an item number, a branch code, a customer
 * code), base64url-encoded so a client treats it as a token rather than a value it can
 * construct. Nothing is signed: a forged cursor can only move the caller to another
 * position in a list they may already read in full.
 */
final class Cursors {

    private Cursors() {
    }

    static String encode(String sortKey) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sortKey.getBytes(StandardCharsets.UTF_8));
    }

    /** The sort key to continue after, or {@code null} for the first page. */
    static String decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String key = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            if (key.isBlank()) {
                throw invalid();
            }
            return key;
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return ApiException.badRequest("invalid_cursor",
                "That cursor is not one this API issued. Start again without a cursor.");
    }
}
