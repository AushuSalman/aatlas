package com.aatlas.competition.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every hour, eBay listings older than 24 hours are taken out of the stored Retail and bulk checks
 * ({@link BuyChecks#expireEbay}) - eBay's API licence caps how long its listing data may be kept.
 *
 * <p>Hourly under a ShedLock, so a listing is gone within 25 hours of being fetched. Tenants come
 * from {@code tenants} (no row-level security); the purge runs as each tenant. One tenant's failure
 * never stops the rest.
 */
@Component
class EbayRetention {

    private static final Logger log = LoggerFactory.getLogger(EbayRetention.class);

    private final JdbcTemplate jdbc;
    private final BuyChecks checks;
    private final AatlasClock clock;
    private final boolean enabled;

    EbayRetention(JdbcTemplate jdbc, BuyChecks checks, AatlasClock clock,
            @Value("${aatlas.competition.ebay-retention.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.checks = checks;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${aatlas.competition.ebay-retention.cron:0 15 * * * *}", zone = "UTC")
    @SchedulerLock(name = "competition-ebay-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        if (!enabled) {
            return;
        }
        Instant now = clock.now();
        List<UUID> tenants = jdbc.queryForList("select id from tenants", UUID.class);
        int rewritten = 0;
        for (UUID tenant : tenants) {
            try {
                rewritten += TenantContext.runAs(TenantContext.Actor.system(tenant), () -> checks.expireEbay(tenant, now));
            } catch (RuntimeException ex) {
                log.warn("eBay listings for tenant {} could not be expired: {}", tenant, ex.getMessage());
            }
        }
        if (rewritten > 0) {
            log.info("eBay retention: listings older than 24h removed from {} stored buy checks", rewritten);
        }
    }
}
