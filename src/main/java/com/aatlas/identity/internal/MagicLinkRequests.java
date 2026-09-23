package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

final class MagicLinkRequests {

    private MagicLinkRequests() {
    }

    @Schema(name = "MagicLinkStartRequest", description = "Mail a one-time sign-in link to a registered address.")
    record Start(
            @Schema(example = "alex@kestrelsupply.com")
                    @NotBlank(message = "An email address is needed to continue.")
                    @Email(message = "That does not look like an email address.")
                    @Size(max = 254, message = "That email address is too long.")
                    String email) {
        Start {
            email = email == null ? null : email.strip();
        }
    }

    @Schema(name = "MagicLinkConsumeRequest", description = "The token from the emailed link.")
    record Consume(
            @NotBlank(message = "The sign-in link is incomplete.")
                    @Size(max = 512, message = "That is not a sign-in token.")
                    String token) {
    }
}
