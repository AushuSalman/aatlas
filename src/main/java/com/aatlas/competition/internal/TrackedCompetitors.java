package com.aatlas.competition.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@code tracked_competitors}: the domains a tenant follows. */
@Repository
class TrackedCompetitors {

    /** Each active competitor costs two scraper requests per item looked up; this caps the bill. */
    static final int MAX_ACTIVE = 10;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    TrackedCompetitors(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    record Tracked(UUID id, String domain, String name, String source, boolean active, String discovery) {
    }

    @Transactional(readOnly = true)
    List<Tracked> list(UUID tenantId) {
        return jdbc.query("""
                select id, domain, name, source, active, discovery::text as discovery
                  from tracked_competitors where tenant_id = ? order by active desc, name
                """, (rs, i) -> new Tracked(rs.getObject("id", UUID.class), rs.getString("domain"),
                        rs.getString("name"), rs.getString("source"), rs.getBoolean("active"),
                        rs.getString("discovery")), tenantId);
    }

    @Transactional(readOnly = true)
    List<Tracked> active(UUID tenantId) {
        return list(tenantId).stream().filter(Tracked::active).limit(MAX_ACTIVE).toList();
    }

    /** Adds the domain, or renames and re-activates it when it is already tracked. */
    @Transactional
    Tracked upsert(UUID tenantId, String domain, String name, String source, Map<String, Object> discovery) {
        String d = normalise(domain);
        String n = name == null || name.isBlank() ? d : name.strip();
        String disc;
        try {
            disc = discovery == null ? null : json.writeValueAsString(discovery);
        } catch (Exception ex) {
            disc = null;
        }
        jdbc.update("""
                insert into tracked_competitors (tenant_id, domain, name, source, active, discovery)
                values (?, ?, ?, ?, true, cast(? as jsonb))
                on conflict (tenant_id, domain) do update
                    set name = excluded.name, active = true,
                        discovery = coalesce(excluded.discovery, tracked_competitors.discovery), updated_at = now()
                """, tenantId, d, n.length() > 120 ? n.substring(0, 120) : n, source, disc);
        return list(tenantId).stream().filter(t -> t.domain().equals(d)).findFirst().orElseThrow();
    }

    @Transactional
    boolean setActive(UUID tenantId, UUID id, boolean active) {
        return jdbc.update("update tracked_competitors set active = ?, updated_at = now() where tenant_id = ? and id = ?",
                active, tenantId, id) > 0;
    }

    @Transactional
    boolean delete(UUID tenantId, UUID id) {
        return jdbc.update("delete from tracked_competitors where tenant_id = ? and id = ?", tenantId, id) > 0;
    }

    /** "https://www.HomeDepot.com/p/x" → "homedepot.com". Null or unusable → IllegalArgumentException. */
    static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A domain is required.");
        }
        String s = raw.strip().toLowerCase(Locale.ROOT);
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        String host;
        try {
            host = URI.create(s).getHost();
        } catch (RuntimeException ex) {
            host = null;
        }
        if (host == null || !host.contains(".") || host.length() > 253) {
            throw new IllegalArgumentException("Not a domain: " + raw);
        }
        return host.replaceFirst("^www\\.", "");
    }
}
