package com.aatlas.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * RS256 signing keys for the access token.
 *
 * <p>In production the pair comes from Vault or the cloud KMS through
 * {@code aatlas.jwt.private-key-pem} / {@code public-key-pem}. When neither is set — a
 * fresh developer checkout — a throwaway pair is generated at startup, which is why every
 * restart signs everyone out locally. That is deliberate: it makes a missing secret
 * obvious in development instead of shipping a default key to production.
 */
@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Bean
    RSAKey rsaKey(AatlasProperties properties) {
        AatlasProperties.Jwt jwt = properties.jwt();
        if (hasText(jwt.privateKeyPem()) && hasText(jwt.publicKeyPem())) {
            return new RSAKey.Builder(readPublicKey(jwt.publicKeyPem()))
                    .privateKey(readPrivateKey(jwt.privateKeyPem()))
                    .keyID("aatlas-signing-key")
                    .build();
        }

        log.warn("No aatlas.jwt key pair configured; generating an ephemeral one. "
                + "Tokens will not survive a restart. Never use this in production.");
        KeyPair generated = generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) generated.getPublic())
                .privateKey((RSAPrivateKey) generated.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    @Bean
    JWKSource<SecurityContext> jwkSource(RSAKey rsaKey) {
        JWKSet set = new JWKSet(rsaKey);
        return (selector, context) -> selector.select(set);
    }

    @Bean
    JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey rsaKey) {
        try {
            return NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot build the JWT decoder from the configured key", ex);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot generate an RSA key pair", ex);
        }
    }

    private static RSAPublicKey readPublicKey(String pem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(stripPem(pem));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("aatlas.jwt.public-key-pem is not a valid X.509 public key", ex);
        }
    }

    private static RSAPrivateKey readPrivateKey(String pem) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(stripPem(pem));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("aatlas.jwt.private-key-pem is not a valid PKCS#8 private key", ex);
        }
    }

    private static String stripPem(String pem) {
        return pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
    }
}
