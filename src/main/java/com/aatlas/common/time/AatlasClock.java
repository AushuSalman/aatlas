package com.aatlas.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * "Now", as the platform sees it.
 *
 * <p>The prototype freezes now at 1 September 2026 so the seeded story lines up. Real
 * tenants run on the system clock; the sample tenant keeps a frozen one, configured by
 * {@code aatlas.clock.*} and eventually per tenant in {@code tenant_settings}.
 *
 * <p>Nothing in the engines may call {@code Instant.now()} or {@code LocalDate.now()}
 * directly — an ArchUnit rule enforces that, because a hidden system clock is what makes
 * a golden-file test pass in the morning and fail at night.
 */
public interface AatlasClock {

    Clock clock();

    default Instant now() {
        return clock().instant();
    }

    default LocalDate today() {
        return LocalDate.now(clock());
    }

    default ZonedDateTime zonedNow() {
        return ZonedDateTime.now(clock());
    }

    default ZoneId zone() {
        return clock().getZone();
    }

    /** Follows the machine clock. Production default. */
    static AatlasClock system(ZoneId zone) {
        Clock clock = Clock.system(zone);
        return () -> clock;
    }

    /** Frozen. Demo tenants and golden-file tests. */
    static AatlasClock fixed(Instant instant, ZoneId zone) {
        Clock clock = Clock.fixed(instant, zone);
        return () -> clock;
    }
}
