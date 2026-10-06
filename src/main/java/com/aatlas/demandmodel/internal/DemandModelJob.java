package com.aatlas.demandmodel.internal;

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
 * Retraining on each tenant's own schedule ({@link DemandTrainingSchedule}): daily, weekly, monthly or
 * only on request, and optionally when new sales come in. A one-minute tick under a ShedLock, so one
 * pod trains; tenants one after another. Tenants come from {@code tenants} (no row-level security);
 * the run itself is as the tenant. One tenant's failure never stops the rest.
 */
@Component
class DemandModelJob {

    private static final Logger log = LoggerFactory.getLogger(DemandModelJob.class);

    private final DemandTrainingSchedule schedule;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    DemandModelJob(DemandTrainingSchedule schedule, JdbcTemplate jdbc,
            @Value("${aatlas.demand-model.enabled:true}") boolean enabled,
            @Value("${aatlas.demand-model.schedule-enabled:true}") boolean scheduleEnabled) {
        this.schedule = schedule;
        this.jdbc = jdbc;
        this.enabled = enabled && scheduleEnabled;
    }

    @Scheduled(fixedDelayString = "${aatlas.demand-model.tick:PT1M}", initialDelayString = "${aatlas.demand-model.tick:PT1M}")
    @SchedulerLock(name = "demand-model-train", lockAtMostFor = "PT2H", lockAtLeastFor = "PT20S")
    public void tick() {
        if (!enabled) {
            return;
        }
        List<UUID> tenants = jdbc.queryForList("select id from tenants where status = 'ACTIVE'", UUID.class);
        int trained = 0;
        for (UUID tenant : tenants) {
            try {
                if (TenantContext.runAs(TenantContext.Actor.system(tenant), () -> schedule.tick(tenant))) {
                    trained++;
                }
            } catch (RuntimeException ex) {
                log.warn("Demand model schedule for tenant {} could not run: {}", tenant, ex.getMessage());
            }
        }
        if (trained > 0) {
            log.info("Demand model: trained for {} tenant(s) on their schedules", trained);
        }
    }
}
