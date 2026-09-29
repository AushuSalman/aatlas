package com.aatlas.competition.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.history.Catalogue;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Settings → Competitor price sources, and the background jobs they start.
 *
 * <p>The keys are the platform's; a tenant only switches configured sources on or off. The first
 * save (or any save asking for it) fetches prices for the whole catalogue in the background; after
 * that, every product import fetches prices for the products it brought in, silently, with the
 * result in the bell.
 */
@Service
class PriceSourcesService {

    private static final Logger log = LoggerFactory.getLogger(PriceSourcesService.class);

    /** Sources shown in Settings but not yet selectable. */
    static final List<String> COMING_SOON = List.of(CompetitorSites.KEY);

    /** A running job whose progress has not moved for this long was cut off (a restart). */
    static final Duration STALLED_AFTER = Duration.ofMinutes(10);

    private final CompetitionService competition;
    private final PriceSourceSettings settings;
    private final RefreshJobs jobs;
    private final RefreshJobRunner runner;
    private final Catalogue catalogue;

    PriceSourcesService(CompetitionService competition, PriceSourceSettings settings, RefreshJobs jobs,
            RefreshJobRunner runner, Catalogue catalogue) {
        this.competition = competition;
        this.settings = settings;
        this.jobs = jobs;
        this.runner = runner;
        this.catalogue = catalogue;
    }

    CompetitionDtos.SourcesSettings get() {
        UUID tenant = TenantContext.requireTenantId();
        PriceSourceSettings.Settings s = settings.get(tenant);
        List<CompetitionDtos.SourceView> views = competition.allProviders().stream()
                .map(p -> new CompetitionDtos.SourceView(p.key(), p.label(), p.plan(), p.available(),
                        s.enabled().contains(p.key()) && p.available() && !COMING_SOON.contains(p.key()),
                        COMING_SOON.contains(p.key())))
                .toList();
        return new CompetitionDtos.SourcesSettings(s.configured(), views, catalogue.products().size(),
                view(jobs.latest(tenant)));
    }

    CompetitionDtos.SaveSourcesResult save(CompetitionDtos.SaveSourcesRequest req) {
        UUID tenant = TenantContext.requireTenantId();
        List<String> wanted = req.enabledSources() == null ? List.of()
                : req.enabledSources().stream().map(k -> k.strip().toLowerCase(Locale.ROOT)).distinct().toList();
        for (String key : wanted) {
            ShoppingProvider p = competition.allProviders().stream().filter(x -> x.key().equals(key)).findFirst()
                    .orElseThrow(() -> ApiException.badRequest("unknown_source", "Unknown price source: " + key));
            if (COMING_SOON.contains(key)) {
                throw ApiException.badRequest("source_coming_soon", p.label() + " is not available yet.");
            }
            if (!p.available()) {
                throw ApiException.badRequest("source_unavailable", p.label() + " is not set up on the server yet.");
            }
        }
        boolean firstTime = !settings.get(tenant).configured();
        settings.save(tenant, wanted, TenantContext.currentUserId().orElse(null));

        CompetitionDtos.JobView job = null;
        boolean fetch = req.fetchNow() != null ? req.fetchNow() : firstTime;
        if (fetch && !wanted.isEmpty()) {
            List<String> items = catalogue.products().stream().map(Catalogue.ProductRef::itemNumber).toList();
            if (!items.isEmpty()) {
                job = view(start(tenant, "setup", wanted, items));
            }
        }
        return new CompetitionDtos.SaveSourcesResult(get(), job);
    }

    CompetitionDtos.JobView job(UUID id) {
        RefreshJobs.Job j = jobs.get(TenantContext.requireTenantId(), id);
        if (j == null) {
            throw ApiException.notFound("Price check", id);
        }
        return view(j);
    }

    CompetitionDtos.JobView latestJob() {
        return view(jobs.latest(TenantContext.requireTenantId()));
    }

    /**
     * A product import finished: price what it brought in, silently, when the tenant has chosen its
     * sources. Before that choice nothing runs - the post-import prompt asks for it.
     */
    void onProductsImported(UUID tenantId, UUID batchId) {
        PriceSourceSettings.Settings s = settings.get(tenantId);
        if (!s.configured() || competition.providersFor(s.enabled()).isEmpty()) {
            return;
        }
        List<String> items = jobs.itemsOfBatch(tenantId, batchId);
        if (items.isEmpty()) {
            return;
        }
        start(tenantId, "import", s.enabled(), items);
        log.info("Import {}: fetching competitor prices for {} products in the background", batchId, items.size());
    }

    private RefreshJobs.Job start(UUID tenant, String trigger, List<String> sources, List<String> items) {
        UUID id = jobs.create(tenant, trigger, sources, items, TenantContext.currentUserId().orElse(null));
        runner.startAfterCommit(tenant, id);
        return jobs.get(tenant, id);
    }

    private CompetitionDtos.JobView view(RefreshJobs.Job j) {
        if (j == null) {
            return null;
        }
        String status = j.status();
        String error = j.error();
        boolean active = "queued".equals(status) || "running".equals(status);
        if (active && j.updatedAt() != null
                && j.updatedAt().isBefore(OffsetDateTime.now().minus(STALLED_AFTER))) {
            status = "failed";
            error = "The price check was interrupted. Start it again from Settings.";
        }
        List<String> labels = competition.allProviders().stream().filter(p -> j.sources().contains(p.key()))
                .map(ShoppingProvider::label).toList();
        return new CompetitionDtos.JobView(j.id(), j.trigger(), status, labels, j.total(), j.done(), j.priced(),
                j.observations(), j.failed(), error, j.startedAt(), j.finishedAt());
    }
}
