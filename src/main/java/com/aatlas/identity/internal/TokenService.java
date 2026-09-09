package com.aatlas.identity.internal;

import com.aatlas.common.time.AatlasClock;
import com.aatlas.config.AatlasProperties;
import com.aatlas.identity.SeatRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.JwsAlgorithms;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Mints the pair of credentials a signed-in client holds.
 *
 * <p>The access token is a short-lived RS256 JWT the API verifies with a public key and
 * no database round trip - that is what keeps an authenticated request cheap. The price of
 * statelessness is that it cannot be withdrawn early, so it is deliberately small: fifteen
 * minutes by default, from {@code aatlas.jwt.access-token-ttl}.
 *
 * <p>The refresh token is the opposite: opaque, stored, single-use and revocable. It is
 * 256 bits from {@link SecureRandom} and never written to the database in the clear - only
 * its SHA-256 is. A plain digest is correct here and would be wrong for a password:
 * the input is full-entropy random, so there is nothing to brute-force and nothing for
 * BCrypt's work factor to buy.
 */
@Service
class TokenService {

    /** 256 bits. Long enough that guessing is not a threat model. */
    private static final int REFRESH_TOKEN_BYTES = 32;

    /** The claim names {@code TenantContextFilter} and {@code SecurityConfig} already read. */
    private static final String CLAIM_TENANT = "tid";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";

    private final JwtEncoder jwtEncoder;
    private final AatlasProperties properties;
    private final AatlasClock clock;
    private final SecureRandom random = new SecureRandom();

    TokenService(JwtEncoder jwtEncoder, AatlasProperties properties, AatlasClock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** An access token and the second it expires, for the caller to hand to the client. */
    record AccessToken(String value, Instant expiresAt, Duration ttl) {
    }

    /**
     * A refresh token: the secret the client keeps, and the digest the database keeps.
     * The two never travel together beyond this record.
     */
    record RefreshToken(String value, byte[] hash, Instant expiresAt) {
    }

    AccessToken issueAccessToken(UUID tenantId, UUID userId, String email, SeatRole seatRole) {
        Instant now = clock.now();
        Duration ttl = properties.jwt().accessTokenTtl();
        Instant expiresAt = now.plus(ttl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                // Not before now: a token is never valid earlier than it was minted, even
                // if a verifier's clock runs behind the issuer's.
                .notBefore(now)
                .subject(userId.toString())
                // A unique id per token, so a specific one can be named in an audit trail
                // or added to a deny list without revoking a whole session.
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TENANT, tenantId.toString())
                .claim(CLAIM_ROLE, seatRole.wireValue())
                .claim(CLAIM_EMAIL, email)
                .build();

        JwsHeader header = JwsHeader.with(() -> JwsAlgorithms.RS256).build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, expiresAt, ttl);
    }

    RefreshToken issueRefreshToken() {
        byte[] secret = new byte[REFRESH_TOKEN_BYTES];
        random.nextBytes(secret);
        // URL-safe and unpadded so the value survives a query string, a header and a
        // cookie without re-encoding.
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        return new RefreshToken(value, sha256(value), clock.now().plus(properties.jwt().refreshTokenTtl()));
    }

    /** The digest stored in {@code refresh_tokens.token_hash}. */
    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required of every JVM; if it is missing the platform is broken.
            throw new IllegalStateException("SHA-256 is not available on this JVM", ex);
        }
    }
}
