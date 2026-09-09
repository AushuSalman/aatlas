package com.aatlas.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Scheduled work, locked so that only one pod runs it.
 *
 * <p>The nightly engine run, the FX refresh and the rollup maintenance are all
 * {@code @Scheduled} methods. With four to twelve pods behind a load balancer, every one
 * of them would fire on the same cron — recomputing the same tenant several times and
 * racing on the snapshot tables. ShedLock takes a row-level lock in PostgreSQL so exactly
 * one pod does the work and the rest move on.
 *
 * <p>Kubernetes CronJobs would also work; this keeps the schedule beside the code that
 * implements it, which is easier to reason about when a job's timing and its logic change
 * together.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfig {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new org.springframework.jdbc.core.JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
