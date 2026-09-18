package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The bodies of the single sign-on calls. */
final class SsoRequests {

    private SsoRequests() {
    }

    /**
     * What the browser brought back from the provider, and what it kept to prove the flow is
     * its own: the PKCE verifier and the nonce.
     */
    @Schema(name = "SsoExchangeRequest")
    record Exchange(
            @NotBlank(message = "The authorization code is missing.") @Size(max = 2048) String code,
            @NotBlank(message = "The PKCE verifier is missing.") @Size(min = 43, max = 128) String codeVerifier,
            @NotBlank(message = "The nonce is missing.") @Size(max = 256) String nonce,
            @NotBlank(message = "The redirect URI is missing.") @Size(max = 2048) String redirectUri,
            /** Apple only, from its first form post. A display name, never trusted for anything else. */
            @Size(max = 120) String fullName) {
    }

    @Schema(name = "SsoLoginRequest")
    record Login(@NotBlank(message = "The sign-in ticket is missing.") @Size(max = 512) String ticket) {
    }
}
