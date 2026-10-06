package com.aatlas.competition.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.Catalogue;
import com.aatlas.notifications.Notifications;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Settings → Price-check schedules: the only way competitor prices are fetched in the background.
 *
 * <p>A schedule is a named profile - all products, some categories, or picked items; some of the
 * sources switched on in Settings; a start; once, or every N hours, days, weeks or months. A
 * per-minute tick ({@link PriceScheduleTicker}) starts the runs that are due, each a
 * {@code competitor_refresh_jobs} row with progress, and the bell says when one ends. "All" and
 * category schedules take in products added later; picked items stay as picked.
 */
@Service
class PriceSchedulesService {

    private static final Logger log = LoggerFactory.getLogger(PriceSchedulesService.class);

    static final int MAX_SCHEDULES = 20;
    static final int MAX_ITEMS = 5000;
    private static final int RECENT_RUNS = 5;
    private static final List<String> SCOPES = List.of("all", "categories", "items");
    private static final List<String> UNITS = List.of("hours", "days", "weeks", "months");

    private final PriceSchedules schedules;
    private final RefreshJobs jobs;
    private final RefreshJobRunner runner;
    private final PriceSourcesService sources;
    private final PriceSourceSettings settings;
    private final CompetitionService competition;
    private final Catalogue catalogue;
    private final Notifications notifications;
    private final AatlasClock clock;

    PriceSchedulesService(PriceSchedules schedules, RefreshJobs jobs, RefreshJobRunner runner,
            PriceSourcesService sources, PriceSourceSettings settings, CompetitionService competition,
            Catalogue catalogue, Notifications notifications, AatlasClock clock) {
        this.schedules = schedules;
        this.jobs = jobs;
        this.runner = runner;
        this.sources = sources;
        this.settings = settings;
        this.competition = competition;
        this.catalogue = catalogue;
        this.notifications = notifications;
        this.clock = clock;
    }

    CompetitionDtos.SchedulesView list() {
        UUID tenant = TenantContext.requireTenantId();
        List<Catalogue.ProductRef> products = catalogue.products();
        List<CompetitionDtos.ScheduleView> views = schedules.list(tenant).stream()
                .map(s -> view(tenant, s, products)).toList();
        return new CompetitionDtos.SchedulesView(views, categories(products), products.size(), MAX_SCHEDULES,
                ScheduleMath.MIN_HOURS);
    }

    CompetitionDtos.ScheduleView create(CompetitionDtos.ScheduleRequest req) {
        UUID tenant = TenantContext.requireTenantId();
        if (schedules.count(tenant) >= MAX_SCHEDULES) {
            throw ApiException.badRequest("too_many_schedules",
                    "A workspace can have " + MAX_SCHEDULES + " price-check schedules. Delete one first.");
        }
        PriceSchedules.Fields f = fields(tenant, req, null);
        UUID id = schedules.create(tenant, f, TenantContext.currentUserId().orElse(null));
        return afterWrite(tenant, id);
    }

    CompetitionDtos.ScheduleView update(UUID id, CompetitionDtos.ScheduleRequest req) {
        UUID tenant = TenantContext.requireTenantId();
        PriceSchedules.Schedule existing = existing(tenant, id);
        schedules.update(tenant, id, fields(tenant, req, existing));
        return afterWrite(tenant, id);
    }

    void delete(UUID id) {
        UUID tenant = TenantContext.requireTenantId();
        if (!schedules.delete(tenant, id)) {
            throw ApiException.notFound("Price-check schedule", id);
        }
    }

    /** Runs the schedule now, outside its timing - its next scheduled run stays as it was. */
    CompetitionDtos.RunNowResult runNow(UUID id) {
        UUID tenant = TenantContext.requireTenantId();
        PriceSchedules.Schedule s = existing(tenant, id);
        if (running(tenant, s.id())) {
            throw ApiException.badRequest("already_running", "“" + s.name() + "” is already running.");
        }
        Instant now = clock.now();
        Start started = start(tenant, s);
        if (started.problem() != null) {
            throw ApiException.badRequest(started.code(), started.problem());
        }
        schedules.ranAt(tenant, s.id(), now);
        return new CompetitionDtos.RunNowResult(view(tenant, schedules.get(tenant, id), catalogue.products()),
                sources.view(started.job()));
    }

    /**
     * The tick for one tenant (bound by the caller): starts every run that is due and moves each
     * schedule on to its next time. A run still going when its next is due is skipped, not stacked.
     *
     * @return runs started
     */
    int runDue(UUID tenant) {
        Instant now = clock.now();
        int started = 0;
        for (PriceSchedules.Schedule s : schedules.due(tenant, now)) {
            Instant next = s.every() == null ? null
                    : ScheduleMath.next(s.startsAt(), s.every(), s.unit(), zone(s.timeZone()), now.plusSeconds(1), now);
            if (!schedules.claim(tenant, s.id(), s.nextRunAt(), next, now)) {
                continue;
            }
            if (running(tenant, s.id())) {
                log.info("Price-check schedule {} is still running; this run is skipped", s.id());
                continue;
            }
            Start run = start(tenant, s);
            if (run.problem() != null) {
                notifications.publish(tenant, "competitor-prices", "Price check “" + s.name() + "” did not run",
                        run.problem(), "/app/settings#price-schedules");
            } else {
                started++;
            }
        }
        return started;
    }

    // ---- running ---------------------------------------------------------------------------

    /** A run started, or why it could not be ({@code code} + {@code problem}). */
    private record Start(RefreshJobs.Job job, String code, String problem) {
    }

    private Start start(UUID tenant, PriceSchedules.Schedule s) {
        List<String> on = settings.get(tenant).enabled();
        List<String> keys = s.sources().stream().filter(on::contains).toList();
        if (competition.providersFor(keys).isEmpty()) {
            return new Start(null, "sources_switched_off",
                    "None of its price sources is switched on in Settings or set up on the server.");
        }
        List<String> items = items(s, catalogue.products());
        if (items.isEmpty()) {
            return new Start(null, "no_products", "No products match it any more.");
        }
        UUID job = jobs.create(tenant, "schedule", s.id(), keys, items, TenantContext.currentUserId().orElse(null));
        runner.startAfterCommit(tenant, job);
        return new Start(jobs.get(tenant, job), null, null);
    }

    private boolean running(UUID tenant, UUID scheduleId) {
        return jobs.forSchedule(tenant, scheduleId, 1).stream().map(sources::view)
                .anyMatch(j -> "queued".equals(j.status()) || "running".equals(j.status()));
    }

    /** A create or edit that is due at once starts now, not at the next tick. */
    private CompetitionDtos.ScheduleView afterWrite(UUID tenant, UUID id) {
        PriceSchedules.Schedule s = schedules.get(tenant, id);
        if (s.active() && s.nextRunAt() != null && !s.nextRunAt().isAfter(clock.now())) {
            runDue(tenant);
        }
        return view(tenant, schedules.get(tenant, id), catalogue.products());
    }

    // ---- reading ---------------------------------------------------------------------------

    private CompetitionDtos.ScheduleView view(UUID tenant, PriceSchedules.Schedule s,
            List<Catalogue.ProductRef> products) {
        List<CompetitionDtos.JobView> runs = jobs.forSchedule(tenant, s.id(), RECENT_RUNS).stream()
                .map(sources::view).toList();
        boolean running = !runs.isEmpty()
                && ("queued".equals(runs.get(0).status()) || "running".equals(runs.get(0).status()));
        String stage = running ? "running" : !s.active() ? "paused" : s.nextRunAt() == null ? "completed" : "scheduled";

        List<String> on = settings.get(tenant).enabled();
        Map<String, ShoppingProvider> byKey = new LinkedHashMap<>();
        competition.allProviders().forEach(p -> byKey.put(p.key(), p));
        List<String> labels = s.sources().stream().map(k -> byKey.containsKey(k) ? byKey.get(k).label() : k).toList();
        List<String> liveKeys = s.sources().stream()
                .filter(k -> on.contains(k) && byKey.containsKey(k) && byKey.get(k).available()).toList();
        List<String> off = s.sources().stream().filter(k -> !liveKeys.contains(k))
                .map(k -> byKey.containsKey(k) ? byKey.get(k).label() : k).toList();

        int count = items(s, products).size();
        // One search per product per source; eBay adds the bulk-lot search a scheduled run makes.
        int perRun = count * liveKeys.size() + (liveKeys.contains("ebay") ? count : 0);
        int perMonth = (int) Math.round(perRun * ScheduleMath.runsPerMonth(s.every(), s.unit()));
        return new CompetitionDtos.ScheduleView(s.id(), s.name(), s.scope(), s.categories(), s.items(), count,
                s.sources(), labels, off, s.startsAt(), s.every(), s.unit(), s.timeZone(), s.active(), stage,
                s.active() ? s.nextRunAt() : null, s.lastRunAt(), perRun, perMonth, runs);
    }

    /** The item numbers a schedule covers today. */
    static List<String> items(PriceSchedules.Schedule s, List<Catalogue.ProductRef> products) {
        return switch (s.scope()) {
            case "categories" -> {
                List<String> wanted = s.categories().stream().map(c -> c.toLowerCase(Locale.ROOT)).toList();
                yield products.stream()
                        .filter(p -> p.category() != null && wanted.contains(p.category().toLowerCase(Locale.ROOT)))
                        .map(Catalogue.ProductRef::itemNumber).toList();
            }
            case "items" -> {
                List<String> known = products.stream().map(Catalogue.ProductRef::itemNumber).toList();
                yield s.items().stream().filter(known::contains).toList();
            }
            default -> products.stream().map(Catalogue.ProductRef::itemNumber).toList();
        };
    }

    private static List<String> categories(List<Catalogue.ProductRef> products) {
        return products.stream().map(Catalogue.ProductRef::category).filter(Objects::nonNull).map(String::strip)
                .filter(c -> !c.isEmpty()).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    // ---- writing ---------------------------------------------------------------------------

    private PriceSchedules.Schedule existing(UUID tenant, UUID id) {
        PriceSchedules.Schedule s = schedules.get(tenant, id);
        if (s == null) {
            throw ApiException.notFound("Price-check schedule", id);
        }
        return s;
    }

    private PriceSchedules.Fields fields(UUID tenant, CompetitionDtos.ScheduleRequest req,
            PriceSchedules.Schedule existing) {
        String typed = req.name() == null ? "" : req.name().strip();
        if (typed.length() > 80) {
            throw ApiException.badRequest("invalid_name", "Keep the schedule's name to 80 characters.");
        }
        if (!typed.isEmpty() && schedules.nameTaken(tenant, typed, existing == null ? null : existing.id())) {
            throw ApiException.badRequest("name_taken", "There is already a schedule called “" + typed + "”.");
        }

        String scope = req.scope() == null ? "all" : req.scope().strip().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw ApiException.badRequest("invalid_scope", "Products must be all, some categories, or picked items.");
        }
        List<Catalogue.ProductRef> products = catalogue.products();
        List<String> categories = List.of();
        List<String> items = List.of();
        if ("categories".equals(scope)) {
            Map<String, String> known = new LinkedHashMap<>();
            categories(products).forEach(c -> known.put(c.toLowerCase(Locale.ROOT), c));
            List<String> picked = new ArrayList<>();
            for (String c : req.categories() == null ? List.<String>of() : req.categories()) {
                String hit = c == null ? null : known.get(c.strip().toLowerCase(Locale.ROOT));
                if (hit == null) {
                    throw ApiException.badRequest("unknown_category", "No products are in the category “" + c + "”.");
                }
                if (!picked.contains(hit)) {
                    picked.add(hit);
                }
            }
            if (picked.isEmpty()) {
                throw ApiException.badRequest("no_categories", "Pick at least one category.");
            }
            categories = picked;
        } else if ("items".equals(scope)) {
            Map<String, String> known = new LinkedHashMap<>();
            products.forEach(p -> known.put(p.itemNumber().toLowerCase(Locale.ROOT), p.itemNumber()));
            List<String> picked = new ArrayList<>();
            List<String> unknown = new ArrayList<>();
            for (String i : req.items() == null ? List.<String>of() : req.items()) {
                String hit = i == null ? null : known.get(i.strip().toLowerCase(Locale.ROOT));
                if (hit == null) {
                    unknown.add(i);
                } else if (!picked.contains(hit)) {
                    picked.add(hit);
                }
            }
            if (!unknown.isEmpty()) {
                throw ApiException.badRequest("unknown_items", "Not in your catalogue: "
                        + String.join(", ", unknown.subList(0, Math.min(5, unknown.size())))
                        + (unknown.size() > 5 ? " and " + (unknown.size() - 5) + " more" : "") + ".");
            }
            if (picked.isEmpty()) {
                throw ApiException.badRequest("no_items", "Pick at least one product.");
            }
            if (picked.size() > MAX_ITEMS) {
                throw ApiException.badRequest("too_many_items", "Pick at most " + MAX_ITEMS
                        + " products, or schedule by category.");
            }
            items = picked;
        }

        List<String> on = settings.get(tenant).enabled();
        List<String> keys = req.sources() == null ? List.of()
                : req.sources().stream().filter(Objects::nonNull).map(k -> k.strip().toLowerCase(Locale.ROOT)).distinct()
                        .toList();
        if (keys.isEmpty()) {
            throw ApiException.badRequest("no_sources", "Pick at least one price source.");
        }
        for (String k : keys) {
            if (!on.contains(k)) {
                throw ApiException.badRequest("source_switched_off",
                        "“" + k + "” is not switched on under Competitor price sources.");
            }
        }

        Integer every = req.repeatEvery();
        String unit = req.repeatUnit() == null ? null : req.repeatUnit().strip().toLowerCase(Locale.ROOT);
        if ((every == null) != (unit == null || unit.isEmpty())) {
            throw ApiException.badRequest("invalid_repeat", "A repeat needs both a number and hours, days, weeks or months.");
        }
        if (every != null) {
            if (!UNITS.contains(unit) || every < 1 || every > 365) {
                throw ApiException.badRequest("invalid_repeat", "Repeat every 1 to 365 hours, days, weeks or months.");
            }
            if ("hours".equals(unit) && every < ScheduleMath.MIN_HOURS) {
                throw ApiException.badRequest("repeat_too_often",
                        "Repeat every " + ScheduleMath.MIN_HOURS + " hours at the most often.");
            }
        } else {
            unit = null;
        }

        String tz = req.timeZone() == null || req.timeZone().isBlank() ? "UTC" : req.timeZone().strip();
        ZoneId zone;
        try {
            zone = ZoneId.of(tz);
        } catch (DateTimeException ex) {
            throw ApiException.badRequest("invalid_time_zone", "Unknown time zone: " + tz);
        }

        // No name typed: one is made from what it covers and how often, e.g. "Plumbing, HVAC · every day".
        String name = typed.isEmpty()
                ? uniqueName(tenant, autoName(scope, categories, items, every, unit), existing == null ? null : existing.id())
                : typed;

        Instant now = clock.now();
        Instant startsAt = req.startsAt() == null ? now : req.startsAt();
        boolean active = req.active() == null || req.active();
        Instant next = active
                ? ScheduleMath.next(startsAt, every, unit, zone, now, existing == null ? null : existing.lastRunAt())
                : null;
        return new PriceSchedules.Fields(name, scope, categories, items, keys, startsAt, every, unit, tz, active, next);
    }

    /** "All products · every day", "Plumbing, HVAC +1 more · every 2 weeks", "W-12 · once". */
    static String autoName(String scope, List<String> categories, List<String> items, Integer every, String unit) {
        String what = switch (scope) {
            case "categories" -> String.join(", ", categories.subList(0, Math.min(2, categories.size())))
                    + (categories.size() > 2 ? " +" + (categories.size() - 2) + " more" : "");
            case "items" -> items.size() == 1 ? items.get(0) : items.size() + " products";
            default -> "All products";
        };
        String how = every == null ? "once"
                : every == 1 ? "every " + unit.substring(0, unit.length() - 1) : "every " + every + " " + unit;
        String name = what + " · " + how;
        return name.length() > 72 ? name.substring(0, 72).strip() : name;
    }

    /** The name, or the name with " (2)", " (3)"... when another schedule already has it. */
    private String uniqueName(UUID tenant, String base, UUID except) {
        String name = base;
        for (int n = 2; schedules.nameTaken(tenant, name, except); n++) {
            name = base + " (" + n + ")";
        }
        return name;
    }

    private static ZoneId zone(String tz) {
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException ex) {
            return ZoneId.of("UTC");
        }
    }
}
