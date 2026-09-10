package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The token from the mail and the new password. Same length rule as signup; the rest of
 * {@code PasswordPolicy} runs in the service, where the email is known.
 */
@Schema(name = "ResetPasswordRequest")
record ResetPasswordRequest(
        @NotBlank(message = "The reset token is missing.")
                @Size(max = 512, message = "That is not a reset token.")
                String token,

        @Schema(minLength = 8, maxLength = 20)
                @NotBlank(message = "A password is needed to continue.")
                @Size(min = 8, max = 20, message = "Use between 8 and 20 characters.")
                String password) {
}
