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
 * Once a minute, starts the price-check schedules that are due ({@link PriceSchedulesService#runDue}).
 *
 * <p>Under a ShedLock, so one pod ticks however many run. Tenants are read from {@code tenants}
 * (no row-level security); each tenant's schedules are read and run as that tenant. One tenant's
 * failure never stops the rest.
 */
@Component
class PriceScheduleTicker {

    private static final Logger log = LoggerFactory.getLogger(PriceScheduleTicker.class);

    private final JdbcTemplate jdbc;
    private final PriceSchedulesService schedules;
    private final boolean enabled;

    PriceScheduleTicker(JdbcTemplate jdbc, PriceSchedulesService schedules,
            @Value("${aatlas.competition.schedules.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.schedules = schedules;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${aatlas.competition.schedules.tick:PT1M}",
            initialDelayString = "${aatlas.competition.schedules.tick:PT1M}")
    @SchedulerLock(name = "competition-price-schedules", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
    public void tick() {
        if (!enabled) {
            return;
        }
        List<UUID> tenants = jdbc.queryForList("select id from tenants where status = 'ACTIVE'", UUID.class);
        for (UUID tenant : tenants) {
            try {
                int started = TenantContext.runAs(TenantContext.Actor.system(tenant), () -> schedules.runDue(tenant));
                if (started > 0) {
                    log.info("Price-check schedules: started {} run(s) for tenant {}", started, tenant);
                }
            } catch (RuntimeException ex) {
                log.warn("Price-check schedules for tenant {} could not run: {}", tenant, ex.getMessage());
            }
        }
    }
}
