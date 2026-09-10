package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The one field the forgot form has. */
@Schema(name = "ForgotPasswordRequest")
record ForgotPasswordRequest(
        @Schema(example = "alex@kestrelsupply.com")
                @NotBlank(message = "An email address is needed to continue.")
                @Email(message = "That does not look like an email address.")
                @Size(max = 254, message = "That email address is too long.")
                String email) {

    /** Trims the email before {@code @Email} sees it; see {@code SignupRequest}. */
    ForgotPasswordRequest {
        email = email == null ? null : email.strip();
    }
}
