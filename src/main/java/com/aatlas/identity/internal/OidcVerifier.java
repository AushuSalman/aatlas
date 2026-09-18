package com.aatlas.identity.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.time.AatlasClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The server's half of OpenID Connect for Google and Apple.
 *
 * <p>Trades an authorization code for tokens - with the client secret and the PKCE verifier,
 * neither of which a browser should be trusted with the pairing of - then verifies the ID
 * token: signature against the provider's published keys, issuer, audience (our client id),
 * expiry, and the nonce the browser generated. Only an identity that passes all of that
 * leaves this class.
 *
 * <p>Apple has no static client secret. It is a short-lived ES256 JWT signed with the
 * {@code .p8} key from the Apple developer account, minted per exchange.
 */
@Component
class OidcVerifier {

    private static final Logger log = LoggerFactory.getLogger(OidcVerifier.class);

    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String GOOGLE_JWKS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> GOOGLE_ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");

    private static final String APPLE_TOKEN_URL = "https://appleid.apple.com/auth/token";
    private static final String APPLE_JWKS = "https://appleid.apple.com/auth/keys";
    private static final String APPLE_ISSUER = "https://appleid.apple.com";
    private static final String APPLE_RELAY_DOMAIN = "@privaterelay.appleid.com";

    /**
     * What a verified ID token says.
     *
     * @param emailAuthoritative the provider owns the mailbox, not merely verified it once:
     *     a Gmail address or a Workspace domain for Google, any verified address for Apple.
     *     Only then may the identity be linked to an existing account by email.
     */
    record VerifiedIdentity(
            SsoProvider provider,
            String subject,
            String email,
            boolean emailVerified,
            String fullName,
            boolean privateRelay,
            boolean emailAuthoritative) {
    }

    private record ProviderSettings(String clientId, NimbusJwtDecoder decoder) {
    }

    private final Map<SsoProvider, ProviderSettings> providers = new EnumMap<>(SsoProvider.class);
    private final String googleClientSecret;
    private final String appleTeamId;
    private final String appleKeyId;
    private final String applePrivateKeyPem;
    private final AatlasClock clock;
    private final RestClient http = RestClient.create();

    OidcVerifier(
            @Value("${aatlas.sso.google.client-id:}") String googleClientId,
            @Value("${aatlas.sso.google.client-secret:}") String googleClientSecret,
            @Value("${aatlas.sso.apple.client-id:}") String appleClientId,
            @Value("${aatlas.sso.apple.team-id:}") String appleTeamId,
            @Value("${aatlas.sso.apple.key-id:}") String appleKeyId,
            @Value("${aatlas.sso.apple.private-key:}") String applePrivateKeyPem,
            AatlasClock clock) {
        this.googleClientSecret = googleClientSecret;
        this.appleTeamId = appleTeamId;
        this.appleKeyId = appleKeyId;
        this.applePrivateKeyPem = applePrivateKeyPem;
        this.clock = clock;

        if (!googleClientId.isBlank() && !googleClientSecret.isBlank()) {
            providers.put(SsoProvider.GOOGLE, new ProviderSettings(googleClientId,
                    decoder(GOOGLE_JWKS, GOOGLE_ISSUERS, googleClientId)));
        }
        if (!appleClientId.isBlank() && !appleTeamId.isBlank() && !appleKeyId.isBlank() && !applePrivateKeyPem.isBlank()) {
            providers.put(SsoProvider.APPLE, new ProviderSettings(appleClientId,
                    decoder(APPLE_JWKS, Set.of(APPLE_ISSUER), appleClientId)));
        }
        log.info("Single sign-on configured for: {}", providers.isEmpty() ? "none" : providers.keySet());
    }

    boolean isConfigured(SsoProvider provider) {
        return providers.containsKey(provider);
    }

    VerifiedIdentity exchange(SsoProvider provider, String code, String codeVerifier, String nonce, String redirectUri) {
        ProviderSettings settings = providers.get(provider);
        if (settings == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "sso_not_configured",
                    provider.label() + " sign-in is not configured on this server.");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", settings.clientId());
        form.add("client_secret", provider == SsoProvider.GOOGLE ? googleClientSecret : appleClientSecret(settings.clientId()));
        form.add("code_verifier", codeVerifier);

        JsonNode tokens;
        try {
            tokens = http.post()
                    .uri(provider == SsoProvider.GOOGLE ? GOOGLE_TOKEN_URL : APPLE_TOKEN_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            // Usually a code already used, expired, or a redirect_uri that does not match the client.
            log.warn("{} token exchange refused: {} {}", provider, ex.getStatusCode(), ex.getResponseBodyAsString());
            throw exchangeFailed(provider);
        } catch (RestClientException ex) {
            log.error("{} token endpoint unreachable", provider, ex);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "sso_unavailable",
                    provider.label() + " could not be reached. Try again in a moment.");
        }

        String idToken = tokens == null ? null : tokens.path("id_token").asText(null);
        if (idToken == null) {
            log.warn("{} token response had no id_token", provider);
            throw exchangeFailed(provider);
        }

        Jwt jwt;
        try {
            jwt = settings.decoder().decode(idToken);
        } catch (JwtException ex) {
            log.warn("{} ID token rejected: {}", provider, ex.getMessage());
            throw exchangeFailed(provider);
        }
        if (nonce == null || !nonce.equals(jwt.getClaimAsString("nonce"))) {
            log.warn("{} ID token nonce mismatch", provider);
            throw exchangeFailed(provider);
        }

        String email = jwt.getClaimAsString("email");
        if (email == null || email.isBlank()) {
            throw ApiException.badRequest("sso_no_email",
                    provider.label() + " did not share an email address. Allow access to your email and try again.");
        }
        email = email.strip();
        boolean verified = truthy(jwt.getClaims().get("email_verified"));

        if (provider == SsoProvider.GOOGLE) {
            String lower = email.toLowerCase(Locale.ROOT);
            String hd = jwt.getClaimAsString("hd");
            boolean authoritative = verified
                    && (lower.endsWith("@gmail.com") || (hd != null && lower.endsWith("@" + hd.toLowerCase(Locale.ROOT))));
            return new VerifiedIdentity(provider, jwt.getSubject(), email, verified,
                    blankToNull(jwt.getClaimAsString("name")), false, authoritative);
        }
        boolean relay = truthy(jwt.getClaims().get("is_private_email")) || email.toLowerCase(Locale.ROOT).endsWith(APPLE_RELAY_DOMAIN);
        // Apple sends the name only in the first form post, never in the token.
        return new VerifiedIdentity(provider, jwt.getSubject(), email, verified, null, relay, verified && !relay);
    }

    private static NimbusJwtDecoder decoder(String jwks, Set<String> issuers, String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
        OAuth2TokenValidator<Jwt> issuer = jwt -> issuers.contains(jwt.getClaimAsString("iss"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected issuer", null));
        OAuth2TokenValidator<Jwt> aud = jwt -> {
            List<String> audiences = jwt.getAudience();
            return audiences != null && audiences.contains(audience)
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Unexpected audience", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), issuer, aud));
        return decoder;
    }

    /** Apple's client secret: an ES256 JWT, valid five minutes, signed with the account's .p8 key. */
    private String appleClientSecret(String clientId) {
        try {
            String base64 = applePrivateKeyPem
                    .replace("\\n", "\n")
                    .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            Instant now = clock.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(appleTeamId)
                    .subject(clientId)
                    .audience(APPLE_ISSUER)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(appleKeyId).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        } catch (JOSEException | java.security.GeneralSecurityException | IllegalArgumentException ex) {
            log.error("Could not build the Apple client secret; check APPLE_PRIVATE_KEY", ex);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "sso_not_configured",
                    "Apple sign-in is misconfigured on this server.");
        }
    }

    private static ApiException exchangeFailed(SsoProvider provider) {
        return ApiException.badRequest("sso_exchange_failed",
                provider.label() + " sign-in could not be verified. Start again.");
    }

    /** Apple sends booleans as strings; Google as booleans. */
    private static boolean truthy(Object value) {
        return value instanceof Boolean b ? b : value != null && "true".equalsIgnoreCase(value.toString());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
