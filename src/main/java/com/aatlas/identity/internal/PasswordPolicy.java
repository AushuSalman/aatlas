package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What counts as an acceptable password.
 *
 * <p>The rules are chosen from NIST SP 800-63B: length is the requirement that matters,
 * and screening against obvious choices is worth more than composition rules. There is no
 * "must contain a symbol" here on purpose - such rules push people towards
 * {@code Password1!}, which is both predictable and annoying, and they measurably reduce
 * entropy rather than raise it.
 *
 * <p>Separate from the bean-validation annotations on {@link SignupRequest} because these
 * checks need context the record does not have: the address being registered, and a
 * decision about what to compare it to.
 */
@Component
class PasswordPolicy {

    /** Minimum length. Eight is the floor NIST SP 800-63B sets for a user-chosen secret. */
    static final int MIN_LENGTH = 8;

    /**
     * Maximum length, in characters.
     *
     * <p>A product decision rather than a security one, and it cuts against the guidance
     * above: capping at twenty rules out the passphrases that make length cheap to come by
     * ("correct horse battery staple" is twenty-eight). Worth revisiting - the screen for
     * obvious choices is doing more of the work while this stands.
     */
    static final int MAX_LENGTH = 20;

    /**
     * BCrypt hashes the first 72 bytes and drops the rest without complaint. Rejecting
     * longer input is the only way to keep "the password I typed" and "the password that
     * was checked" the same string.
     *
     * <p>Currently unreachable, and kept anyway. {@link String#length} counts UTF-16 code
     * units, and the worst case is three bytes per unit - a four-byte character costs two
     * units, so it averages two. Twenty characters therefore cannot exceed sixty bytes,
     * and {@link #MAX_LENGTH} subsumes this check.
     *
     * <p>It stays because that relationship is a coincidence of the current numbers rather
     * than a guarantee: raise {@code MAX_LENGTH} past 24 and this becomes load-bearing
     * again. {@code PasswordPolicyTest} pins the arithmetic so the day it starts mattering
     * is not a surprise.
     */
    static final int MAX_BYTES = 72;

    /**
     * A deliberately short screen, not a breach corpus. It stops the handful of values a
     * bored person types to get past a form; catching genuinely leaked passwords needs a
     * k-anonymity lookup against Have I Been Pwned, which belongs behind an interface and
     * a network timeout rather than in a static set.
     */
    private static final Set<String> OBVIOUS = Set.of(
            // Eight characters is where the guessing lists start, so dropping the floor to
            // eight is what makes most of these reachable in the first place.
            "password", "passw0rd", "password1", "password12", "password123",
            "12345678", "123456789", "1234567890", "123456789012", "qwerty12", "qwerty123",
            "qwertyuiop", "abc12345", "iloveyou", "trustno1", "sunshine", "princess",
            "football", "baseball", "superman", "dragon123", "monkey123", "welcome1",
            "welcome123", "letmein1", "letmein123", "admin123", "administrator",
            "changeme", "changeme1", "aatlas123", "passwordpassword");

    /**
     * @throws ApiException 400 with a message the form can show verbatim
     */
    void check(String password, String email) {
        if (password == null || password.isBlank()) {
            throw reject("A password is needed to continue.");
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw reject("Use between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters.");
        }
        // Counted in bytes, not characters: an emoji is four bytes and eighteen of them
        // would exceed BCrypt's limit while looking like a short password.
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw reject("That password is too long. Use at most " + MAX_BYTES + " bytes.");
        }

        String lowered = password.toLowerCase(Locale.ROOT);
        if (OBVIOUS.contains(lowered)) {
            throw reject("That password is too easy to guess. Pick something less common.");
        }
        if (isSingleRepeatedCharacter(password)) {
            throw reject("That password is one character repeated. Pick something less predictable.");
        }
        if (containsEmailLocalPart(lowered, email)) {
            throw reject("Your password should not contain your email address.");
        }
    }

    private static boolean isSingleRepeatedCharacter(String password) {
        return password.chars().distinct().count() == 1;
    }

    /**
     * The part before the {@code @}, which is usually a name and therefore the first thing
     * anyone targeting this account would try. Only checked at four characters or more, so
     * a two-letter address does not ban half the dictionary.
     */
    private static boolean containsEmailLocalPart(String loweredPassword, String email) {
        if (email == null) {
            return false;
        }
        int at = email.indexOf('@');
        String localPart = (at > 0 ? email.substring(0, at) : email).strip().toLowerCase(Locale.ROOT);
        return localPart.length() >= 4 && loweredPassword.contains(localPart);
    }

    private static ApiException reject(String message) {
        return ApiException.badRequest("weak_password", message);
    }
}
