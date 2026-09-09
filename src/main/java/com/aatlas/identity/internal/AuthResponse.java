package com.aatlas.identity.internal;

import com.aatlas.identity.SeatRole;
import com.aatlas.tenant.CountryCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * What a successful signup or sign-in returns.
 *
 * <p>Shaped to drop straight into the frontend's {@code Session} in
 * {@code src/lib/platform/types.ts}, so pointing the client at this API is a change to
 * {@code api.ts} and nothing above it. The tokens sit beside the session rather than
 * inside it because they are transport, not domain: the client stores them differently and
 * for a different lifetime.
 *
 * @param accessToken RS256 JWT. Send as {@code Authorization: Bearer <token>}.
 * @param tokenType always {@code Bearer}; present so a generic client need not assume.
 * @param expiresIn seconds until the access token expires, not an absolute time, so a
 *     client with a skewed clock still refreshes on schedule.
 * @param refreshToken opaque and single-use. Exchanging it returns a new pair.
 * @param session what the UI renders immediately, without decoding the token.
 */
@Schema(name = "AuthResponse", description = "Tokens plus the session the UI renders.")
record AuthResponse(
        String accessToken, String tokenType, long expiresIn, String refreshToken, SessionView session) {

    static final String BEARER = "Bearer";

    /**
     * The signed-in state.
     *
     * @param dataSource always null here. A new tenant has no transaction history, and a
     *     pricing tool with nothing to price from has nothing to recommend, so the client
     *     routes to onboarding on exactly this being absent.
     */
    @Schema(name = "SessionView")
    record SessionView(
            UserView user,
            String company,
            CountryCode country,
            String currency,
            Instant signedInAt,
            boolean isNewAccount,
            Object dataSource) {
    }

    /** The person, as the header and the avatar need them. */
    @Schema(name = "UserView")
    record UserView(UUID id, String name, String email, String title, SeatRole role, String initials) {
    }

    static AuthResponse of(
            TokenService.AccessToken accessToken, String refreshToken, SessionView session) {
        return new AuthResponse(
                accessToken.value(), BEARER, accessToken.ttl().toSeconds(), refreshToken, session);
    }

    /**
     * First letter of the first two words, uppercased - the same rule the prototype uses,
     * so an account created through the API and one created in the browser show the same
     * avatar.
     *
     * <p>Splits on whitespace by code point so a name outside the Basic Multilingual Plane
     * does not yield half a character.
     */
    static String initialsOf(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "U";
        }
        String[] words = fullName.strip().split("\\s+");
        StringBuilder initials = new StringBuilder(2);
        for (int i = 0; i < words.length && initials.length() < 2; i++) {
            if (!words[i].isEmpty()) {
                initials.appendCodePoint(words[i].codePointAt(0));
            }
        }
        return initials.toString().toUpperCase(Locale.ROOT);
    }
}
