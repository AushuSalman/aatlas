package com.aatlas.config;

import com.aatlas.common.time.AatlasClock;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one clock the application is allowed to read.
 *
 * <p>The prototype freezes "now" at 1 September 2026 so the seeded 26-month ledger and
 * every screenshot line up. Production runs on the system clock; only the sample tenant
 * stays frozen, which is what keeps the demo story reproducible without a second codebase.
 */
@Configuration
public class ClockConfig {

    private static final Logger log = LoggerFactory.getLogger(ClockConfig.class);

    @Bean
    AatlasClock aatlasClock(AatlasProperties properties) {
        AatlasProperties.ClockSettings settings = properties.clock();
        ZoneId zone = ZoneId.of(settings.zone());

        if (settings.fixed()) {
            log.warn("Clock is FROZEN at {} ({}). Correct for the demo tenant, wrong for production.",
                    settings.fixedAt(), zone);
            return AatlasClock.fixed(settings.fixedAt(), zone);
        }
        return AatlasClock.system(zone);
    }
}
