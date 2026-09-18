package com.aatlas.identity.internal;

import com.aatlas.identity.SeatRole;
import com.aatlas.tenant.CountryCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * The two-step signup form, posted once.
 *
 * <p>The client collects identity on step one and the seat on step two, but submits the
 * whole thing at the end - there is nothing to persist halfway through, and a half-created
 * account is worse than a form the user can still edit.
 *
 * <p>Field-level rules here catch shape; {@code SignupService} enforces the rules that
 * need the database or a policy decision. The split matters: a 400 from this record costs
 * nothing, while a duplicate-email check costs a query.
 *
 * @param fullName as typed. Used for the display name and the initials on the avatar.
 * @param email the sign-in identifier. Case and padding are normalised before storage.
 * @param password never stored; hashed with BCrypt and discarded.
 * @param company optional - the form marks it so and falls back to a default.
 * @param country selects the map, the regions and the trading currency.
 * @param role the seat, which decides which workspaces open.
 */
@Schema(name = "SignupRequest", description = "Create a company and its first user.")
record SignupRequest(
        @Schema(example = "Alex Moreno")
                @NotBlank(message = "Your name is needed to continue.")
                @Size(max = 120, message = "That name is too long.")
                String fullName,

        @Schema(example = "alex@kestrelsupply.com")
                @NotBlank(message = "An email address is needed to continue.")
                @Email(message = "That does not look like an email address.")
                @Size(max = 254, message = "That email address is too long.")
                String email,

        // 8 to 20 characters. Note that 20 characters is not the same as 20 bytes: twenty
        // emoji are 80 bytes, past the 72 BCrypt hashes before silently ignoring the rest,
        // so PasswordPolicy still enforces a byte ceiling underneath this.
        // Required unless ssoTicket is sent; SignupService enforces that.
        @Schema(example = "Zephyr!42Bridge", minLength = 8, maxLength = 20)
                @Size(min = 8, max = 20, message = "Use between 8 and 20 characters.")
                String password,

        @Schema(example = "Kestrel Supply Co.")
                @Size(max = 200, message = "That company name is too long.")
                String company,

        @Schema(example = "US")
                @NotNull(message = "Choose a country.")
                CountryCode country,

        @Schema(example = "both")
                @NotNull(message = "Choose the seat you work in.")
                SeatRole role,

        // The challenge from /email/verify/start, confirmed. Required unless
        // aatlas.auth.require-email-verification is off.
        @Schema(example = "01923f6e-7b1a-7c2e-9d3f-5a6b7c8d9e0f")
                UUID verificationId,

        // From POST /auth/sso/{provider}. Replaces the password and the email verification:
        // the provider verified the address, and the account signs in with the provider.
        @Schema(description = "Ticket from the Google / Apple exchange, in place of a password.")
                @Size(max = 512, message = "That is not a sign-in ticket.")
                String ssoTicket) {

    /**
     * Trims the email before {@code @Email} sees it.
     *
     * <p>Bean validation runs against the record's accessors, after this constructor, so a
     * client that pastes in a leading or trailing space gets a normal signup rather than a
     * "not a valid email address" 400 - {@code email_normalised} already promises to be
     * indifferent to exactly this, and the validation should not be stricter than the
     * storage it is guarding.
     */
    SignupRequest {
        email = email == null ? null : email.strip();
    }
}
