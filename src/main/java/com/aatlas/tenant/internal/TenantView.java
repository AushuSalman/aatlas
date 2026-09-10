package com.aatlas.tenant.internal;

import com.aatlas.tenant.TenantDirectory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** The company profile as the header and the Settings screen show it. */
@Schema(name = "Tenant")
record TenantView(UUID id, String name, String slug, String status, Instant createdAt) {

    static TenantView of(TenantDirectory.TenantInfo info) {
        return new TenantView(info.id(), info.name(), info.slug(), info.status(), info.createdAt());
    }
}
