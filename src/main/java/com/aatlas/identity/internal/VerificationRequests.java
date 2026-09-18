package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** The bodies of the three email-verification calls. */
final class VerificationRequests {

    private VerificationRequests() {
    }

    @Schema(name = "StartVerificationRequest", description = "Mail a six-digit code to an address.")
    record Start(
            @Schema(example = "alex@kestrelsupply.com")
                    @NotBlank(message = "An email address is needed to continue.")
                    @Email(message = "That does not look like an email address.")
                    @Size(max = 254, message = "That email address is too long.")
                    String email,

            @Schema(example = "Alex Moreno")
                    @Size(max = 120, message = "That name is too long.")
                    String fullName) {

        Start {
            email = email == null ? null : email.strip();
        }
    }

    @Schema(name = "ResendVerificationRequest")
    record Resend(@NotNull(message = "The verification is missing.") UUID challengeId) {
    }

    @Schema(name = "ConfirmVerificationRequest")
    record Confirm(
            @NotNull(message = "The verification is missing.") UUID challengeId,

            @Schema(example = "482913")
                    @NotBlank(message = "Enter the six-digit code.")
                    @Pattern(regexp = "\\s*\\d{6}\\s*", message = "The code is six digits.")
                    String code) {
    }
}
