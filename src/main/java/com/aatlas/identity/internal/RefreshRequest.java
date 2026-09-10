package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The refresh token as it was handed out, for {@code /auth/refresh} and {@code /auth/logout}. */
@Schema(name = "RefreshRequest")
record RefreshRequest(
        @NotBlank(message = "A refresh token is needed.")
                @Size(max = 512, message = "That is not a refresh token.")
                String refreshToken) {
}
