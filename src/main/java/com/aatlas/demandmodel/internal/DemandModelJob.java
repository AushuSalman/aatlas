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
 * Nightly retraining: every ACTIVE tenant's demand model, one after another, under a ShedLock
 * so one pod does it. Runs after the outcomes and tuner jobs so a night's recorded sales are
 * in the history it reads. Tenants come from {@code tenants} (no row-level security); the run
 * itself is as the tenant.
 */
@Component
class DemandModelJob {

    private static final Logger log = LoggerFactory.getLogger(DemandModelJob.class);

    private final DemandModelService service;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    DemandModelJob(DemandModelService service, JdbcTemplate jdbc,
            @Value("${aatlas.demand-model.enabled:true}") boolean enabled) {
        this.service = service;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${aatlas.demand-model.cron:0 15 5 * * *}", zone = "UTC")
    @SchedulerLock(name = "demand-model-train", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void nightly() {
        if (!enabled) {
            return;
        }
        List<UUID> tenants = jdbc.queryForList("select id from tenants where status = 'ACTIVE'", UUID.class);
        int trained = 0;
        for (UUID tenant : tenants) {
            try {
                var status = TenantContext.runAs(TenantContext.Actor.system(tenant), () -> service.train(tenant));
                if (status.trained()) {
                    trained++;
                }
            } catch (RuntimeException ex) {
                log.warn("Demand model for tenant {} could not be trained: {}", tenant, ex.getMessage());
            }
        }
        log.info("Demand model job: {} of {} tenants trained", trained, tenants.size());
    }
}
