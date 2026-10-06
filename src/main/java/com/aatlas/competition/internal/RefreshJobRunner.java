package com.aatlas.competition.internal;

import com.aatlas.common.tenant.TenantContext;
import com.aatlas.competition.internal.CompetitionDtos.RefreshRow;
import com.aatlas.notifications.Notifications;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a competitor-price job in the background: item by item, progress written after each,
 * and one notification in the bell at the end. Nothing rethrows past {@link #execute} - every
 * ending writes a terminal status, or the screen would watch a job that never finishes.
 */
@Component
class RefreshJobRunner {

    private static final Logger log = LoggerFactory.getLogger(RefreshJobRunner.class);

    private final ObjectProvider<RefreshJobRunner> self;
    private final RefreshJobs jobs;
    private final CompetitionService competition;
    private final Notifications notifications;
    private final PriceSchedules schedules;

    RefreshJobRunner(ObjectProvider<RefreshJobRunner> self, RefreshJobs jobs, CompetitionService competition,
            Notifications notifications, PriceSchedules schedules) {
        this.schedules = schedules;
        this.self = self;
        this.jobs = jobs;
        this.competition = competition;
        this.notifications = notifications;
    }

    /** Starts the job once the transaction that created it commits (at once when there is none). */
    void startAfterCommit(UUID tenantId, UUID jobId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            self.getObject().execute(tenantId, jobId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.getObject().execute(tenantId, jobId);
            }
        });
    }

    @Async
    public void execute(UUID tenantId, UUID jobId) {
        TenantContext.runAs(TenantContext.Actor.system(tenantId), () -> {
            try {
                run(tenantId, jobId);
            } catch (RuntimeException ex) {
                log.error("Competitor-price job {} failed", jobId, ex);
                try {
                    jobs.finished(tenantId, jobId, "failed", "The price check stopped unexpectedly. Run it again from Settings.");
                    notifications.publish(tenantId, "competitor-prices", "Competitor prices could not be fetched",
                            "The price check stopped unexpectedly. You can run it again from Settings.",
                            "/app/settings#price-schedules");
                } catch (RuntimeException nested) {
                    log.error("Competitor-price job {} failed and could not be marked", jobId, nested);
                }
            }
            return null;
        });
    }

    private void run(UUID tenantId, UUID jobId) {
        RefreshJobs.Job job = jobs.get(tenantId, jobId);
        if (job == null) {
            return;
        }
        jobs.started(tenantId, jobId);
        List<ShoppingProvider> chosen = competition.providersFor(job.sources());
        if (chosen.isEmpty()) {
            jobs.finished(tenantId, jobId, "failed", "None of the chosen price sources is available on the server.");
            notifications.publish(tenantId, "competitor-prices", "Competitor prices could not be fetched",
                    "None of the price sources you chose is available right now.", "/app/settings#price-sources");
            return;
        }
        int done = 0;
        int priced = 0;
        int observations = 0;
        int failed = 0;
        int bulkPriced = 0;
        String firstPriced = null;
        // A scheduled run also keeps the buy side's bulk-lot price fresh - eBay only, when it is one of its sources.
        boolean scheduled = job.scheduleId() != null;
        boolean withBulk = scheduled && chosen.stream().anyMatch(p -> "ebay".equals(p.key()));
        for (String item : job.items()) {
            if (withBulk) {
                try {
                    if (competition.refreshBulkBenchmark(item)) {
                        bulkPriced++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("Competitor-price job {}: bulk price for {} failed: {}", jobId, item, ex.getMessage());
                }
            }
            try {
                RefreshRow row = competition.refreshItem(item, chosen, List.of());
                if ("saved".equals(row.status())) {
                    priced++;
                    observations += row.kept();
                    if (firstPriced == null) {
                        firstPriced = row.item();
                    }
                } else if ("failed".equals(row.status())) {
                    failed++;
                }
            } catch (RuntimeException ex) {
                log.warn("Competitor-price job {}: item {} failed: {}", jobId, item, ex.getMessage());
                failed++;
            }
            done++;
            jobs.progress(tenantId, jobId, done, priced, observations, failed);
        }
        jobs.finished(tenantId, jobId, "done", null);

        String sources = String.join(", ", chosen.stream().map(ShoppingProvider::label).toList());
        String scope = "import".equals(job.trigger()) ? "new products" : "products";
        String body = priced + " of " + job.total() + " " + scope + " now have competitor prices from " + sources + "."
                + (failed > 0 ? " " + failed + " could not be searched (a source's limit or an error) - they will be "
                        + "tried again next time." : "")
                + (withBulk ? " Bulk-lot buying prices refreshed for " + bulkPriced + "." : "")
                + " Open Sell → Competition to see them.";
        String link = firstPriced == null ? "/app/sell?panel=competition"
                : "/app/sell?panel=competition&item=" + java.net.URLEncoder.encode(firstPriced,
                        java.nio.charset.StandardCharsets.UTF_8);
        String schedule = scheduled ? schedules.name(tenantId, job.scheduleId()) : null;
        String title = priced > 0 ? "Competitor prices updated" : "No competitor prices found";
        notifications.publish(tenantId, "competitor-prices",
                schedule == null ? title : title + " - " + schedule,
                schedule == null ? body : "Price check “" + schedule + "”: " + body, link);
        log.info("Competitor-price job {} ({}): {} of {} items priced, {} observations, {} failed", jobId,
                job.trigger(), priced, job.total(), observations, failed);
    }
}
