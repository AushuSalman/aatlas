package com.aatlas.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the application needs to know that is not Spring's own. Bound from
 * {@code aatlas.*}, validated at startup, and documented for the IDE by the
 * configuration-processor.
 */
@ConfigurationProperties(prefix = "aatlas")
public record AatlasProperties(
        Role role,
        Jwt jwt,
        Cors cors,
        ClockSettings clock,
        Cache cache,
        Snapshots snapshots,
        Storage storage,
        Rls rls) {

    /** Which job this process does. One artifact, three roles. */
    public enum Role {
        API,
        ENGINE_WORKER,
        INGEST_WORKER
    }

    /**
     * Access tokens are stateless RS256 JWTs, short-lived; refresh tokens are opaque,
     * hashed in {@code refresh_token}, and rotated on every use.
     */
    public record Jwt(
            String issuer,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            String privateKeyPem,
            String publicKeyPem) {
    }

    /** The Next.js origins allowed to call the API with credentials. */
    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * The fixture clock. The prototype freezes now at 1 Sep 2026; production runs on the
     * real clock and only the sample tenant stays frozen.
     */
    public record ClockSettings(boolean fixed, Instant fixedAt, String zone) {
    }

    /**
     * {@code redis} once a cluster exists — required for more than one pod, because two
     * pods with private caches disagree. {@code caffeine} keeps a laptop running without
     * Redis installed.
     */
    public record Cache(Mode mode) {
        public enum Mode {
            CAFFEINE,
            REDIS
        }
    }

    /**
     * How a request behaves when the snapshot it needs is missing or stale. Controllers
     * never compute an item-by-store walk inline: they answer 202 and let a worker fill it.
     */
    public record Snapshots(boolean computeOnMiss, Duration staleAfter) {
    }

    /** S3-compatible object storage for uploads, exports and RFQ attachments. */
    public record Storage(String bucket, String region, String endpoint, boolean pathStyleAccess) {
    }

    /**
     * PostgreSQL row-level security. Off until the migrations that create the policies
     * have run, then on everywhere as defence in depth behind the {@code tenant_id} column.
     */
    public record Rls(boolean enabled, String sessionVariable) {
    }
}
