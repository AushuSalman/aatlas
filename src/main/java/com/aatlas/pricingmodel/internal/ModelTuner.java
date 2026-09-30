package com.aatlas.pricingmodel.internal;

import com.aatlas.common.cache.CacheNames;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.DealSummaries;
import com.aatlas.decisions.DecisionOutcomes;
import com.aatlas.history.DecisionPatterns;
import com.aatlas.history.PricingModel;
import com.aatlas.history.PricingModel.Side;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The pricing model's training loop: re-fits a few of a tenant's knobs from the tenant's
 * own decisions and their measured outcomes.
 *
 * <p>The chains already adapt live - the lean, the phase-in and the measured price
 * sensitivity per item. What they cannot do on their own is move the knobs those steps
 * read. This does, for one side at a time: it reads the evidence, runs each rule below,
 * and writes the result as that side's <b>learned</b> settings beside the hand-set ones
 * ({@code pricing_model_settings.learned}, with a {@link LearnedNote} per key). The chain
 * then runs on defaults, then learned, then hand-set ({@link PricingModel.Config#layered}):
 * a hand-set value always wins, and a side's learned values count only while that side's
 * "retune from your results" toggle is on.
 *
 * <p>Every rule has an evidence gate and produces at most one value, clamped to the
 * registry's range. A rule whose gate does not pass writes nothing, and since a run
 * replaces the side's learned map outright, a value learned on thinner evidence last time
 * fades rather than lingers. The gates are deliberately conservative: five decisions and
 * a lean confidence of 0.4 before anything moves, eight before a cap widens.
 *
 * <p><b>Sell.</b> {@code elasticity.prior} from the tenant-wide median of measured
 * outcomes (five or more); {@code trustRamp.launch} up when applied prices lean more than
 * 5% above what was phased in and down when more than 5% below; {@code learning.maxMove}
 * widened to the lean when the lean is wider than the cap (eight decisions, confidence
 * 0.5); {@code demand.maxMove} a point smaller when more applied prices hurt volume than
 * helped over eight or more measured. <b>Buy.</b> {@code buy.target.gapShare} toward the
 * median when agreed costs lean more than 3% over the target and toward the best price
 * when they beat it by more than 1%; {@code buy.phaseIn.launch} up when agreed costs beat
 * the phased-in target by more than 5% and down when they sit more than 5% over it.
 *
 * <p>The window knobs are read from the tenant's current effective model (hand-set over
 * previous learned); the {@code learning.maxMove} comparison is against the hand-set-or-
 * default cap, so a widened cap cannot talk itself back down the next night. Each note's
 * {@code from} is the previous learned value, else the registry default.
 *
 * <p>Nightly for every ACTIVE tenant, both sides, at 05:00 UTC under a ShedLock - after
 * the outcomes job at 04:30 has measured the day's decisions - and on demand for one
 * tenant from the settings screen. Tenants come from {@code tenants} (no row-level
 * security); everything else runs as the tenant. The engines read the row per request, so
 * only this module's own cache of the settings view needs evicting.
 */
@Component
class ModelTuner {

    /** Deals read per side per run; the lean itself decays with age anyway. */
    static final int DEAL_ROWS = 500;

    /** Measured outcomes before the price-sensitivity prior is re-fitted. */
    static final int PRIOR_MIN_OUTCOMES = 5;
    /** Decisions and lean confidence before a lean moves a launch or target knob. */
    static final int LEAN_MIN_DECISIONS = 5;
    static final double LEAN_MIN_CONFIDENCE = 0.4;
    /** Decisions and confidence before the lean's own cap widens. */
    static final int CAP_MIN_DECISIONS = 8;
    static final double CAP_MIN_CONFIDENCE = 0.5;
    /** Measured outcomes before their verdicts shrink the demand nudge. */
    static final int VERDICT_MIN_MEASURED = 8;

    private static final Logger log = LoggerFactory.getLogger(ModelTuner.class);

    /** One side's learned settings after a run, exactly as written to the row. */
    record TuneResult(String side, Map<String, PricingModel.Setting> learned, Map<String, LearnedNote> notes,
            Instant learnedAt) {
    }

    private final PricingModelRepository settings;
    private final DealSummaries deals;
    private final DecisionOutcomes outcomes;
    private final CacheManager caches;
    private final JdbcTemplate jdbc;
    private final AatlasClock clock;
    private final boolean enabled;

    ModelTuner(PricingModelRepository settings, DealSummaries deals, DecisionOutcomes outcomes, CacheManager caches,
            JdbcTemplate jdbc, AatlasClock clock,
            @Value("${aatlas.pricing-model.tune-enabled:true}") boolean enabled) {
        this.settings = settings;
        this.deals = deals;
        this.outcomes = outcomes;
        this.caches = caches;
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${aatlas.pricing-model.tune-cron:0 0 5 * * *}", zone = "UTC")
    @SchedulerLock(name = "pricing-model-tune", lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    public void nightly() {
        if (!enabled) {
            return;
        }
        List<UUID> tenants = jdbc.queryForList("select id from tenants where status = 'ACTIVE'", UUID.class);
        int runs = 0;
        int learned = 0;
        for (UUID tenant : tenants) {
            for (Side side : Side.values()) {
                try {
                    TuneResult result = TenantContext.runAs(TenantContext.Actor.system(tenant), () -> tune(tenant, side));
                    runs++;
                    learned += result.learned().size();
                } catch (RuntimeException ex) {
                    log.warn("Pricing model ({} side) for tenant {} could not be retuned: {}", side.key(), tenant,
                            ex.getMessage());
                }
            }
        }
        log.info("Pricing model tuner: {} runs across {} tenants, {} learned settings in all", runs, tenants.size(),
                learned);
    }

    /**
     * Runs every rule of one side for one tenant and writes what they learned: that
     * side's learned map becomes exactly the rules' output, the other side's is kept, the
     * row is created (with no hand-set overrides) when the tenant never saved, and the
     * settings view's cache is dropped for both sides. The caller binds the tenant.
     */
    TuneResult tune(UUID tenantId, Side side) {
        Instant now = clock.now();
        LocalDate today = clock.today();
        PricingModelEntity row = settings.findById(tenantId).orElse(null);
        Map<String, PricingModel.Setting> overrides = row == null ? Map.of() : row.getSettings();
        Map<String, PricingModel.Setting> previous = row == null ? Map.of() : row.getLearned();
        PricingModel.Config hand = PricingModel.Config.of(overrides);
        PricingModel.Config current = PricingModel.Config.layered(previous, overrides);

        Fit fit = new Fit(previous);
        if (side == Side.BUY) {
            tuneBuy(fit, current, today);
        } else {
            tuneSell(fit, hand, current, today);
        }

        if (row == null) {
            row = new PricingModelEntity(tenantId, Map.of(), null, now);
        }
        row.learn(side, fit.learned, fit.notes, now);
        settings.saveAndFlush(row);
        evict(tenantId);
        log.info("Pricing model tuned on the {} side for tenant {}: {} learned setting{} {}", side.key(), tenantId,
                fit.learned.size(), fit.learned.size() == 1 ? "" : "s", fit.learned.keySet());
        return new TuneResult(side.key(), fit.learned, fit.notes, now);
    }

    // ---- sell ----------------------------------------------------------------------------

    private void tuneSell(Fit fit, PricingModel.Config hand, PricingModel.Config current, LocalDate today) {
        // Price sensitivity: what applied prices actually did to volume, across every item measured.
        outcomes.learnedAcrossItems(today).ifPresent(l -> {
            if (l.n() >= PRIOR_MIN_OUTCOMES && l.elasticity() != null) {
                BigDecimal to = fit.clamp(PricingModel.ELASTICITY_PRIOR, l.elasticity().setScale(2, RoundingMode.HALF_UP));
                fit.put(PricingModel.ELASTICITY_PRIOR, to, l.n(), "Measured across " + l.n()
                        + " applied prices: a 1% price rise cost about " + plain(to.abs()) + "% of sales.");
            }
        });

        DecisionPatterns.Learning lean = lean(Side.SELL, current, today);
        int n = lean.decisions();
        double bias = lean.biasPct();

        // Where a new item starts on the trust ramp: further along when you keep applying above it.
        if (solid(lean)) {
            PricingModel.Parameter launch = parameter(PricingModel.TRUST_RAMP_LAUNCH);
            if (bias > 5) {
                BigDecimal to = fit.clamp(PricingModel.TRUST_RAMP_LAUNCH, launch.defaultValue().add(new BigDecimal("20")));
                fit.put(PricingModel.TRUST_RAMP_LAUNCH, to, n, "You apply prices well above what is phased in ("
                        + signed(bias) + "% across " + decisions(n) + "), so new items now start " + plain(to)
                        + "% of the way to their target.");
            } else if (bias < -5) {
                BigDecimal to = fit.clamp(PricingModel.TRUST_RAMP_LAUNCH, launch.defaultValue().subtract(BigDecimal.TEN));
                fit.put(PricingModel.TRUST_RAMP_LAUNCH, to, n, "You apply prices well below what is phased in ("
                        + signed(bias) + "% across " + decisions(n) + "), so new items now start " + plain(to)
                        + "% of the way to their target.");
            }
        }

        // The lean's own cap: widened to the lean once it is well evidenced and wider than the cap.
        if (n >= CAP_MIN_DECISIONS && lean.confidence() >= CAP_MIN_CONFIDENCE) {
            BigDecimal cap = hand.value(PricingModel.LEARNING_MAX_MOVE);
            if (Math.abs(bias) > cap.doubleValue()) {
                BigDecimal to = fit.clamp(PricingModel.LEARNING_MAX_MOVE, BigDecimal.valueOf(Math.ceil(Math.abs(bias))));
                fit.put(PricingModel.LEARNING_MAX_MOVE, to, n, "Your decisions lean " + signed(bias)
                        + "%; the lean was capped at " + plain(cap) + "%, so the cap is now " + plain(to) + "%.");
            }
        }

        // The demand nudge: smaller when applied prices hurt volume more often than they helped.
        DecisionOutcomes.Verdicts verdicts = outcomes.verdicts(today);
        if (verdicts != null && verdicts.measured() >= VERDICT_MIN_MEASURED && verdicts.hurt() > verdicts.worked()) {
            PricingModel.Parameter demand = parameter(PricingModel.DEMAND_MAX_MOVE);
            BigDecimal to = fit.clamp(PricingModel.DEMAND_MAX_MOVE, demand.defaultValue().subtract(BigDecimal.ONE));
            fit.put(PricingModel.DEMAND_MAX_MOVE, to, verdicts.measured(),
                    "More applied prices hurt volume than helped in the last year (" + verdicts.hurt() + " against "
                            + verdicts.worked() + " of " + verdicts.measured() + " measured), so demand nudges are smaller: at most "
                            + plain(to) + "% instead of " + plain(demand.defaultValue()) + "%.");
        }
    }

    // ---- buy -----------------------------------------------------------------------------

    private void tuneBuy(Fit fit, PricingModel.Config current, LocalDate today) {
        DecisionPatterns.Learning lean = lean(Side.BUY, current, today);
        if (!solid(lean)) {
            return;
        }
        int n = lean.decisions();
        double bias = lean.biasPct();

        // Where between the best price and the median the target sits.
        PricingModel.Parameter gap = parameter(PricingModel.BUY_TARGET_GAP_SHARE);
        if (bias > 3) {
            BigDecimal to = fit.clamp(PricingModel.BUY_TARGET_GAP_SHARE, gap.defaultValue().add(new BigDecimal("15")));
            fit.put(PricingModel.BUY_TARGET_GAP_SHARE, to, n, "Agreed costs land above the target (" + signed(bias)
                    + "% across " + decisions(n) + "), so the target now sits closer to the market median: " + plain(to)
                    + "% of the way from the best price.");
        } else if (bias < -1) {
            BigDecimal to = fit.clamp(PricingModel.BUY_TARGET_GAP_SHARE, gap.defaultValue().subtract(BigDecimal.TEN));
            fit.put(PricingModel.BUY_TARGET_GAP_SHARE, to, n, "You beat the target (" + signed(bias) + "% across "
                    + decisions(n) + "), so it now aims closer to the best price: " + plain(to)
                    + "% of the way to the median.");
        }

        // Where a new item starts on the phase-in.
        PricingModel.Parameter launch = parameter(PricingModel.BUY_PHASE_IN_LAUNCH);
        if (bias < -5) {
            BigDecimal to = fit.clamp(PricingModel.BUY_PHASE_IN_LAUNCH, launch.defaultValue().add(new BigDecimal("20")));
            fit.put(PricingModel.BUY_PHASE_IN_LAUNCH, to, n, "You agree costs well under what is phased in (" + signed(bias)
                    + "% across " + decisions(n) + "), so new items now start " + plain(to)
                    + "% of the way to their target.");
        } else if (bias > 5) {
            BigDecimal to = fit.clamp(PricingModel.BUY_PHASE_IN_LAUNCH, launch.defaultValue().subtract(BigDecimal.TEN));
            fit.put(PricingModel.BUY_PHASE_IN_LAUNCH, to, n, "Agreed costs land well above what is phased in ("
                    + signed(bias) + "% across " + decisions(n) + "), so new items now start " + plain(to)
                    + "% of the way to their target.");
        }
    }

    // ---- evidence ------------------------------------------------------------------------

    /**
     * The tenant-wide lean of one side: every deal of that side in that side's window,
     * reduced by the same maths the chain runs per item. The window knob comes from the
     * tenant's current effective model.
     */
    private DecisionPatterns.Learning lean(Side side, PricingModel.Config current, LocalDate today) {
        boolean buy = side == Side.BUY;
        String windowKey = buy ? PricingModel.BUY_LEARNING_WINDOW_DAYS : PricingModel.LEARNING_WINDOW_DAYS;
        LocalDate since = today.minusDays(current.value(windowKey).intValue());
        List<DealSummaries.Acceptance> rows = deals.acceptance(buy ? DealSummaries.BUY : DealSummaries.SELL, null, null,
                since, DEAL_ROWS);
        List<DecisionPatterns.Acceptance> patterns = rows.stream()
                .map(r -> new DecisionPatterns.Acceptance(r.date(), r.suggested(), r.actual(),
                        DecisionPatterns.Acceptance.TENANT))
                .toList();
        return DecisionPatterns.learn(patterns, today, current, side);
    }

    private static boolean solid(DecisionPatterns.Learning lean) {
        return lean.decisions() >= LEAN_MIN_DECISIONS && lean.confidence() >= LEAN_MIN_CONFIDENCE;
    }

    private void evict(UUID tenantId) {
        Cache cache = caches.getCache(CacheNames.PRICING_MODEL);
        if (cache == null) {
            return;
        }
        for (Side side : Side.values()) {
            cache.evict(tenantId + ":" + side.name());
        }
    }

    // ---- what a run learned ---------------------------------------------------------------

    /** Collects one side's learned values and their notes; clamps to the registry and remembers what each was before. */
    private static final class Fit {

        private final Map<String, PricingModel.Setting> previous;
        final Map<String, PricingModel.Setting> learned = new LinkedHashMap<>();
        final Map<String, LearnedNote> notes = new LinkedHashMap<>();

        Fit(Map<String, PricingModel.Setting> previous) {
            this.previous = previous == null ? Map.of() : previous;
        }

        /** {@code raw} held within the parameter's range, with a tidy scale. */
        BigDecimal clamp(String key, BigDecimal raw) {
            PricingModel.Parameter p = parameter(key);
            BigDecimal v = raw;
            if (v.compareTo(p.min()) < 0) {
                v = p.min();
            }
            if (v.compareTo(p.max()) > 0) {
                v = p.max();
            }
            v = v.stripTrailingZeros();
            return v.scale() < 0 ? v.setScale(0) : v;
        }

        void put(String key, BigDecimal to, int evidence, String reason) {
            PricingModel.Setting prior = previous.get(key);
            BigDecimal from = prior != null && prior.value() != null ? prior.value() : parameter(key).defaultValue();
            learned.put(key, PricingModel.Setting.value(to));
            notes.put(key, new LearnedNote(reason, evidence, from, to));
        }
    }

    // ---- small helpers --------------------------------------------------------------------

    private static PricingModel.Parameter parameter(String key) {
        return PricingModel.parameter(key)
                .orElseThrow(() -> new IllegalStateException("Unknown pricing-model parameter " + key));
    }

    static String plain(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    /** A percentage lean to one decimal, signed: {@code +6.3}, {@code -7}. */
    static String signed(double v) {
        String s = plain(BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP));
        return v > 0 ? "+" + s : s;
    }

    private static String decisions(int n) {
        return n + " decision" + (n == 1 ? "" : "s");
    }
}
