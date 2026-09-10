package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The sign-in form. No length rule on the password here: a wrong one is a 401, not a 400. */
@Schema(name = "LoginRequest")
record LoginRequest(
        @Schema(example = "alex@kestrelsupply.com")
                @NotBlank(message = "An email address is needed to continue.")
                @Email(message = "That does not look like an email address.")
                @Size(max = 254, message = "That email address is too long.")
                String email,

        @Schema(example = "Zephyr!42Bridge")
                @NotBlank(message = "A password is needed to continue.")
                @Size(max = 200, message = "That password is too long.")
                String password) {

    /** Trims the email before {@code @Email} sees it; see {@code SignupRequest}. */
    LoginRequest {
        email = email == null ? null : email.strip();
    }
}
