package com.aatlas.competition.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.history.Catalogue;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Settings → Competitor price sources, and the background jobs they start.
 *
 * <p>The keys are the platform's; a tenant only switches configured sources on or off. Saving fetches
 * nothing: competitor prices are checked only when one of the tenant's price-check schedules says so
 * ({@link PriceSchedulesService}) - never on their own after a save or an import.
 */
@Service
class PriceSourcesService {

    /** Sources shown in Settings but not yet selectable. */
    static final List<String> COMING_SOON = List.of(CompetitorSites.KEY);

    /** A running job whose progress has not moved for this long was cut off (a restart). */
    static final Duration STALLED_AFTER = Duration.ofMinutes(10);

    private final CompetitionService competition;
    private final PriceSourceSettings settings;
    private final RefreshJobs jobs;
    private final Catalogue catalogue;

    PriceSourcesService(CompetitionService competition, PriceSourceSettings settings, RefreshJobs jobs,
            Catalogue catalogue) {
        this.competition = competition;
        this.settings = settings;
        this.jobs = jobs;
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

    CompetitionDtos.SourcesSettings save(CompetitionDtos.SaveSourcesRequest req) {
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
        settings.save(tenant, wanted, TenantContext.currentUserId().orElse(null));
        return get();
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

    /** A run as the screens show it; one that stopped moving (a restart) reads as failed. */
    CompetitionDtos.JobView view(RefreshJobs.Job j) {
        if (j == null) {
            return null;
        }
        String status = j.status();
        String error = j.error();
        boolean active = "queued".equals(status) || "running".equals(status);
        if (active && j.updatedAt() != null
                && j.updatedAt().isBefore(OffsetDateTime.now().minus(STALLED_AFTER))) {
            status = "failed";
            error = "The price check was interrupted. Run it again from Settings.";
        }
        List<String> labels = competition.allProviders().stream().filter(p -> j.sources().contains(p.key()))
                .map(ShoppingProvider::label).toList();
        return new CompetitionDtos.JobView(j.id(), j.trigger(), j.scheduleId(), status, labels, j.total(), j.done(),
                j.priced(), j.observations(), j.failed(), error, j.startedAt(), j.finishedAt());
    }
}
