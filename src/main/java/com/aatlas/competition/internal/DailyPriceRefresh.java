package com.aatlas.competition.internal;

import com.aatlas.common.tenant.TenantContext;
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
 * Every day, fresh competitor prices for every tenant that has chosen its price sources.
 *
 * <p>Runs once a day ({@code aatlas.competition.daily-refresh.cron}, 05:00 UTC by default) under a
 * ShedLock, so one pod does it however many run. Tenants are read from {@code tenants}, which carries
 * no row-level security; everything after that runs as the tenant ({@link TenantContext#runAs}), so
 * each tenant's settings, catalogue and prices stay behind its own policy. Each tenant's check is the
 * same background job Settings starts - saved to {@code competitor_prices}, shown under Sell →
 * Competition, and announced in the bell. One tenant's failure never stops the rest.
 */
@Component
class DailyPriceRefresh {

    private static final Logger log = LoggerFactory.getLogger(DailyPriceRefresh.class);

    /** What Settings shows; keep in step with the default cron below. */
    static final String RUNS_AT = "05:00 UTC";

    private final JdbcTemplate jdbc;
    private final PriceSourcesService sources;
    private final boolean enabled;

    DailyPriceRefresh(JdbcTemplate jdbc, PriceSourcesService sources,
            @Value("${aatlas.competition.daily-refresh.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${aatlas.competition.daily-refresh.cron:0 0 5 * * *}", zone = "UTC")
    @SchedulerLock(name = "competition-daily-refresh", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void run() {
        if (!enabled) {
            return;
        }
        List<UUID> tenants = jdbc.queryForList("select id from tenants where status = 'ACTIVE'", UUID.class);
        int started = 0;
        for (UUID tenant : tenants) {
            try {
                boolean ran = TenantContext.runAs(TenantContext.Actor.system(tenant), () -> sources.startDaily(tenant));
                if (ran) {
                    started++;
                }
            } catch (RuntimeException ex) {
                log.warn("Daily competitor-price check for tenant {} could not start: {}", tenant, ex.getMessage());
            }
        }
        log.info("Daily competitor-price check: started for {} of {} tenants", started, tenants.size());
    }
}
