package com.aatlas.decisions.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Catalogue;
import com.aatlas.history.HistoryCaches;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Measures what applied sell prices did, once their after-window has closed.
 *
 * <p>Nightly for every tenant ({@code aatlas.outcomes.cron}, 04:30 UTC) under a ShedLock, and on
 * demand for one tenant. Each decision is judged at its own branch over {@code window-days} either
 * side (28 by default); one with too few sales is retried while it is under {@code retry-days} old,
 * then left as insufficient. Tenants come from {@code tenants} (no row-level security); everything
 * else runs as the tenant. Measuring moves the model, so the tenant's caches are cleared after.
 */
@Service
class OutcomeMeasurer {

    private static final Logger log = LoggerFactory.getLogger(OutcomeMeasurer.class);

    private final OutcomesJdbc outcomes;
    private final Catalogue catalogue;
    private final SalesHistory sales;
    private final HistoryCaches caches;
    private final JdbcTemplate jdbc;
    private final AatlasClock clock;
    private final boolean enabled;
    private final int windowDays;
    private final int retryDays;

    OutcomeMeasurer(OutcomesJdbc outcomes, Catalogue catalogue, SalesHistory sales, HistoryCaches caches,
            JdbcTemplate jdbc, AatlasClock clock,
            @Value("${aatlas.outcomes.enabled:true}") boolean enabled,
            @Value("${aatlas.outcomes.window-days:28}") int windowDays,
            @Value("${aatlas.outcomes.retry-days:120}") int retryDays) {
        this.outcomes = outcomes;
        this.catalogue = catalogue;
        this.sales = sales;
        this.caches = caches;
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
        this.windowDays = Math.max(7, windowDays);
        this.retryDays = Math.max(this.windowDays, retryDays);
    }

    /** How a run went, for the endpoint and the log. */
    record RunSummary(int looked, int measured, int insufficient, int worked, int hurt, int windowDays) {
    }

    @Scheduled(cron = "${aatlas.outcomes.cron:0 30 4 * * *}", zone = "UTC")
    @SchedulerLock(name = "decision-outcomes", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void nightly() {
        if (!enabled) {
            return;
        }
        List<UUID> tenants = jdbc.queryForList("select id from tenants where status = 'ACTIVE'", UUID.class);
        int measured = 0;
        for (UUID tenant : tenants) {
            try {
                measured += TenantContext.runAs(TenantContext.Actor.system(tenant), this::measureNow).measured();
            } catch (RuntimeException ex) {
                log.warn("Decision outcomes for tenant {} could not be measured: {}", tenant, ex.getMessage());
            }
        }
        log.info("Decision outcomes: {} measured across {} tenants", measured, tenants.size());
    }

    /** Measure every due decision of the bound tenant. */
    RunSummary measureNow() {
        UUID tenant = TenantContext.requireTenantId();
        LocalDate today = clock.today();
        List<OutcomesJdbc.Pending> due = outcomes.pending(tenant, today, windowDays, retryDays);
        int measured = 0;
        int insufficient = 0;
        int worked = 0;
        int hurt = 0;
        for (OutcomesJdbc.Pending p : due) {
            var product = catalogue.product(p.itemNumber()).orElse(null);
            if (product == null) {
                continue;
            }
            UUID storeId = p.storeCode() == null ? null
                    : catalogue.store(p.storeCode()).map(Catalogue.StoreRef::id).orElse(null);
            Window before = new Window(p.date().minusDays(windowDays), p.date().minusDays(1));
            Window after = new Window(p.date(), p.date().plusDays(windowDays - 1L));
            SalesStats s0 = storeId != null ? sales.itemStore(product.id(), storeId, before) : sales.item(product.id(), before);
            SalesStats s1 = storeId != null ? sales.itemStore(product.id(), storeId, after) : sales.item(product.id(), after);
            BigDecimal cost = p.cost() != null && p.cost().signum() > 0 ? p.cost() : unitCost(s0, s1);
            OutcomeMath.Result r = OutcomeMath.measure(
                    new OutcomeMath.Side(s0.units(), s0.revenue(), s0.txns()),
                    new OutcomeMath.Side(s1.units(), s1.revenue(), s1.txns()),
                    windowDays, cost, p.baseline(), p.applied());
            outcomes.save(tenant, p, windowDays, r);
            if ("measured".equals(r.status())) {
                measured++;
                if ("worked".equals(r.verdict())) {
                    worked++;
                } else if ("hurt".equals(r.verdict())) {
                    hurt++;
                }
            } else {
                insufficient++;
            }
        }
        if (measured > 0) {
            caches.evict(tenant);
        }
        return new RunSummary(due.size(), measured, insufficient, worked, hurt, windowDays);
    }

    /** Cost a unit from what was costed in the sales either side, when the decision carried none. */
    private static BigDecimal unitCost(SalesStats a, SalesStats b) {
        BigDecimal cogs = nz(a.cogs()).add(nz(b.cogs()));
        BigDecimal units = nz(a.costedUnits()).add(nz(b.costedUnits()));
        return units.signum() > 0 ? cogs.divide(units, 4, RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
