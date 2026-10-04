package com.aatlas.demandmodel.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.demandmodel.DemandModels;
import com.aatlas.history.Catalogue;
import com.aatlas.history.HistoryCaches;
import com.aatlas.history.SalesHistory;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.protobuf.InvalidProtocolBufferException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tribuo.Model;
import org.tribuo.protos.core.ModelProto;
import org.tribuo.regression.Regressor;

/**
 * Trains, stores and serves a tenant's demand model.
 *
 * <p>A run loads up to two years of weekly sales for the busiest pairs, builds the grid, fits
 * and scores the forest, compares each pair's probed sensitivity with the chain's own
 * regression for the report, and writes the model bytes and the per-pair report to the
 * tenant's row. A run that cannot fit (too little history) writes the reason and drops any
 * earlier model, so a stale one never serves. Served models are deserialised once per tenant
 * and kept in a small cache; a run evicts its tenant.
 */
@Service
class DemandModelService implements DemandModels {

    /** Weeks of history a run loads. */
    static final int WEEKS_BACK = 104;

    private static final Logger log = LoggerFactory.getLogger(DemandModelService.class);

    /** The forecasting models a tenant can choose, in the order they are offered; the first is the default. */
    static final List<ModelOption> MODEL_OPTIONS = List.of(
            new ModelOption(DemandTrainer.AUTO, "Automatic",
                    "Each item uses the model that beat its recent average in two back-tests in a row, and the "
                            + "90-day pace where none did. Recommended."),
            new ModelOption(Smoothers.CHRONOS, Smoothers.label(Smoothers.CHRONOS),
                    "A pretrained deep-learning forecaster. It has forecasts only when its service was running at "
                            + "the last training run."),
            new ModelOption(Smoothers.FOREST, Smoothers.label(Smoothers.FOREST),
                    "Machine-learned on your own sales: reads price, competitor prices and recent demand."),
            new ModelOption(Smoothers.SBA, Smoothers.label(Smoothers.SBA),
                    "Built for items that sell in lumps, with empty weeks between."),
            new ModelOption(Smoothers.TSB, Smoothers.label(Smoothers.TSB),
                    "For lumpy sellers too; fades when an item stops selling."),
            new ModelOption(Smoothers.SES, Smoothers.label(Smoothers.SES),
                    "A weighted average that follows the most recent weeks."));

    private static final String NOT_TRAINED = "Not trained yet. It runs nightly once there is sales history, or train it now.";

    /** A tenant's model as served, with the forecasting model the tenant chose. */
    record Loaded(Instant trainedAt, Model<Regressor> forecast, Model<Regressor> response,
            Map<String, PairRecord> pairs, String forecastModel) {
    }

    private final DemandModelRepository repo;
    private final TrainingRows trainingRows;
    private final SalesHistory salesHistory;
    private final Catalogue catalogue;
    private final PolicyReader policy;
    private final HistoryCaches caches;
    private final AatlasClock clock;
    private final ChronosClient chronos;
    private final int holdoutWeeks;
    private final int trees;
    private final int minWeeks;
    private final int maxPairs;
    private final Cache<UUID, Loaded> loaded = Caffeine.newBuilder()
            .maximumSize(32)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    DemandModelService(DemandModelRepository repo, TrainingRows trainingRows, SalesHistory salesHistory,
            Catalogue catalogue, PolicyReader policy, HistoryCaches caches, AatlasClock clock,
            ChronosClient chronos,
            @Value("${aatlas.demand-model.holdout-weeks:8}") int holdoutWeeks,
            @Value("${aatlas.demand-model.trees:100}") int trees,
            @Value("${aatlas.demand-model.min-weeks:12}") int minWeeks,
            @Value("${aatlas.demand-model.max-pairs:2000}") int maxPairs) {
        this.repo = repo;
        this.trainingRows = trainingRows;
        this.salesHistory = salesHistory;
        this.catalogue = catalogue;
        this.policy = policy;
        this.caches = caches;
        this.clock = clock;
        this.chronos = chronos;
        this.holdoutWeeks = holdoutWeeks;
        this.trees = trees;
        this.minWeeks = minWeeks;
        this.maxPairs = maxPairs;
    }

    @Override
    public Status train() {
        TenantContext.Actor actor = TenantContext.current()
                .orElseThrow(() -> ApiException.forbidden("Sign in to train the demand model."));
        requireGuardrailSeat(actor, "train the demand model");
        return train(actor.tenantId());
    }

    @Override
    @Transactional
    public Status chooseForecastModel(String key) {
        TenantContext.Actor actor = TenantContext.current()
                .orElseThrow(() -> ApiException.forbidden("Sign in to change the forecasting model."));
        requireGuardrailSeat(actor, "change the forecasting model");
        String wanted = key == null ? "" : key.strip();
        if (MODEL_OPTIONS.stream().noneMatch(o -> o.key().equals(wanted))) {
            throw ApiException.badRequest("validation_failed", "Unknown forecasting model \"" + wanted + "\". Choose one of: "
                    + MODEL_OPTIONS.stream().map(ModelOption::key).collect(java.util.stream.Collectors.joining(", ")) + ".");
        }
        UUID tenantId = actor.tenantId();
        DemandModelEntity row = repo.findById(tenantId).orElseGet(() -> DemandModelEntity.fresh(tenantId));
        row.chooseForecastModel(DemandTrainer.AUTO.equals(wanted) ? null : wanted);
        repo.save(row);
        loaded.invalidate(tenantId);
        // The sell chain reads the forecast: recommendations cached under the old choice are stale now.
        caches.evictAfterCommit(tenantId);
        return status(row);
    }

    /** One run for one tenant, as the nightly job and the on-demand call share it. Runs as the tenant. */
    @Transactional
    public Status train(UUID tenantId) {
        LocalDate today = clock.today();
        Instant now = clock.now();
        LocalDate lastWeek = WeeklyGrid.lastCompleteWeek(today);
        TrainingRows.Loaded data = trainingRows.load(tenantId, lastWeek, WEEKS_BACK, maxPairs);
        WeeklyGrid.Grid grid = WeeklyGrid.build(data.sales(), data.competitors(), lastWeek,
                Math.max(minWeeks, holdoutWeeks + 4));
        LocalDate from = grid.rows().stream().map(WeeklyGrid.Row::week).min(Comparator.naturalOrder()).orElse(null);
        DemandModelEntity row = repo.findById(tenantId).orElseGet(() -> DemandModelEntity.fresh(tenantId));
        try {
            DemandTrainer.Settings settings = DemandTrainer.Settings.defaults().withHoldoutWeeks(holdoutWeeks).withTrees(trees);
            DemandTrainer.Result result = DemandTrainer.train(grid, settings, DemandTrainer.FOREST, chronos.orNull());
            Map<String, PairRecord> pairs = new LinkedHashMap<>();
            for (Map.Entry<String, WeeklyGrid.Context> e : grid.contexts().entrySet()) {
                pairs.put(e.getKey(), record(e.getValue(), result.evals().get(e.getKey()), today));
            }
            long usable = pairs.values().stream().filter(PairRecord::usable).count();
            int weeks = grid.contexts().values().stream().mapToInt(WeeklyGrid.Context::weeks).max().orElse(0);
            String note = note(pairs.values(), DemandTrainer.AUTO);
            row.trained(result.forecast().serialize().toByteArray(), result.response().serialize().toByteArray(),
                    pairs, now, result.rows(), weeks, from, lastWeek, holdoutWeeks, result.millis(), note);
            log.info("Demand model for tenant {}: {} rows, {} pairs, {} usable, {} ms", tenantId, result.rows(),
                    pairs.size(), usable, result.millis());
        } catch (IllegalStateException ex) {
            row.untrained("Not enough sales history to train: " + ex.getMessage(), now, grid.rows().size(), from,
                    lastWeek, holdoutWeeks);
            log.info("Demand model for tenant {} not trained: {}", tenantId, ex.getMessage());
        }
        repo.save(row);
        loaded.invalidate(tenantId);
        // The sell chain reads this model: recommendations cached before the run are stale now.
        caches.evictAfterCommit(tenantId);
        return status(row);
    }

    private PairRecord record(WeeklyGrid.Context c, DemandTrainer.PairEval ev, LocalDate today) {
        Double regression = null;
        String basis = null;
        try {
            Optional<Catalogue.ProductRef> product = catalogue.product(c.item());
            if (product.isPresent()) {
                UUID storeId = catalogue.store(c.store()).map(Catalogue.StoreRef::id).orElse(null);
                SalesHistory.Elasticity el = salesHistory.elasticity(product.get().id(), storeId, today);
                if (el != null && el.coefficient() != null) {
                    regression = el.coefficient().doubleValue();
                    basis = el.basis();
                }
            }
        } catch (RuntimeException ex) {
            log.debug("No regression comparison for {}@{}: {}", c.item(), c.store(), ex.getMessage());
        }
        return new PairRecord(c.item(), c.store(), c.category(), c.lastWeek().toString(), c.lastPrice(), c.cost(),
                c.compMedian(), c.lags(), c.medianPrice(), c.weeks(), c.units(), ev.holdoutUnits(),
                finite(ev.errorModel()), finite(ev.errorBaseline()), ev.beats(), ev.usable(), ev.elasticity(),
                regression, basis, ev.forecastNext(), ev.settledNext(), ev.note(), finite(ev.horizonError()),
                finite(ev.horizonBaseline()), ev.forecastUsable(), ev.horizonUnits(), ev.trailingUnits(),
                ev.calibration(), ev.forecastMethod(), ev.forecastNote(), ev.methods());
    }

    /** The forecasting model a tenant chose, as a key; automatic when it chose none, or one this build no longer offers. */
    private static String chosenModel(String stored) {
        return stored != null && MODEL_OPTIONS.stream().anyMatch(o -> o.key().equals(stored)) ? stored
                : DemandTrainer.AUTO;
    }

    /**
     * A stored pair's forecast under a choice of model. A pair stored before every forecaster's back-tests were
     * kept has only the one verdict it was stored with, whatever is chosen.
     */
    static DemandTrainer.Verdict verdict(PairRecord p, String model) {
        if (p.methods() == null || p.methods().isEmpty()) {
            return new DemandTrainer.Verdict(p.forecastMethod() == null ? Smoothers.FOREST : p.forecastMethod(),
                    p.forecastUsable(), p.forecastUsable(), p.horizonError() == null ? Double.NaN : p.horizonError(),
                    p.horizonBaseline() == null ? Double.NaN : p.horizonBaseline(), p.horizonUnits(),
                    p.forecastNote());
        }
        return DemandTrainer.verdict(p.methods(), model);
    }

    /** The run in a sentence, under a choice of model: how many pairs have a forecast in use and from which forecaster. */
    static String note(java.util.Collection<PairRecord> pairs, String model) {
        long priceRead = pairs.stream().filter(PairRecord::usable).count();
        List<DemandTrainer.Verdict> verdicts = pairs.stream().map(p -> verdict(p, model)).toList();
        long inUse = verdicts.stream().filter(DemandTrainer.Verdict::usable).count();
        String tail = "; the read of price is proven on " + priceRead;
        if (!DemandTrainer.AUTO.equals(model)) {
            long proven = verdicts.stream().filter(v -> v.usable() && v.proven()).count();
            String label = Smoothers.label(model);
            return inUse == 0
                    ? label + " is the forecasting model chosen in Settings, but it has no forecasts from the last "
                            + "training run, so the 90-day pace is used" + tail
                    : label + " is the forecasting model chosen in Settings: it forecasts " + inUse + " of "
                            + pairs.size() + " item-branch pairs, and beat the recent average in two back-tests in "
                            + "a row on " + proven + " of them" + tail;
        }
        // By forecaster, most used first: "Croston SBA 12, Random forest 6".
        Map<String, Long> byMethod = new LinkedHashMap<>();
        verdicts.stream().filter(DemandTrainer.Verdict::usable)
                .forEach(v -> byMethod.merge(Smoothers.label(v.method()), 1L, Long::sum));
        String split = byMethod.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(m -> m.getKey() + " " + m.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return "A four-week forecast beat the recent average in two back-tests in a row on " + inUse + " of "
                + pairs.size() + " item-branch pairs" + (split.isEmpty() ? "" : " (" + split + ")") + tail;
    }

    @Override
    @Transactional(readOnly = true)
    public Status status() {
        UUID tenantId = TenantContext.requireTenantId();
        return repo.findById(tenantId).map(this::status).orElse(new Status(false, null, 0, 0, 0, 0, 0, null, null,
                holdoutWeeks, 0, NOT_TRAINED, 0, 0, null, 0, List.of(), modelLabel(DemandTrainer.AUTO),
                DemandTrainer.AUTO, MODEL_OPTIONS));
    }

    private Status status(DemandModelEntity row) {
        // The report is read under the model chosen in Settings today, not the one in force when it trained.
        String model = chosenModel(row.getForecastModel());
        List<PairReport> report = row.getPairs().values().stream()
                .sorted(Comparator.comparing((PairRecord p) -> p.usable() || verdict(p, model).usable()).reversed()
                        .thenComparing(PairRecord::units, Comparator.reverseOrder()))
                .map(p -> report(p, model))
                .toList();
        int usable = (int) row.getPairs().values().stream().filter(PairRecord::usable).count();
        TrainingRows.Pending pending = trainingRows.pending(row.getTenantId(), row.getTrainedAt(), row.getToWeek(),
                clock.today());
        int forecastUsable = (int) report.stream().filter(PairReport::forecastUsable).count();
        boolean scored = row.getPairs().values().stream().anyMatch(p -> p.methods() != null && !p.methods().isEmpty());
        return new Status(row.getModel() != null, row.getTrainedAt(), row.getRows(), row.getPairCount(), usable,
                forecastUsable, row.getWeeks(), row.getFromWeek(), row.getToWeek(),
                row.getHoldoutWeeks() > 0 ? row.getHoldoutWeeks() : holdoutWeeks, row.getTrainMillis(),
                scored ? note(row.getPairs().values(), model) : row.getNote() != null ? row.getNote() : NOT_TRAINED,
                pending.sinceTrained(), pending.inOpenWeek(), pending.countedFrom(), pending.awaitingRetrain(), report,
                modelLabel(model), model, MODEL_OPTIONS);
    }

    private static String modelLabel(String model) {
        return DemandTrainer.AUTO.equals(model) ? "Automatic" : Smoothers.label(model);
    }

    static PairReport report(PairRecord p, String model) {
        DemandTrainer.Verdict v = verdict(p, model);
        return new PairReport(p.item(), p.store(), p.category(), p.weeks(), dec(p.holdoutUnits(), 0),
                pct(p.errorModel()), pct(p.errorBaseline()), p.beats(), p.usable(), dec(p.elasticity(), 2),
                p.regressionElasticity() == null ? null : dec(p.regressionElasticity(), 2), p.regressionBasis(),
                dec(p.lastPrice(), 2), dec(p.forecastNext(), 1), dec(p.settledNext(), 1), p.note(),
                dec(v.nextUnits(), 1), dec(p.trailingUnits(), 1), v.usable(), pct(finite(v.errorShare())),
                pct(finite(v.naiveShare())), Smoothers.label(v.method()), v.note());
    }

    @Override
    public Optional<Forecast> forecast(String itemNumber, String storeCode, BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            return Optional.empty();
        }
        Loaded l = load(TenantContext.requireTenantId());
        if (l == null) {
            return Optional.empty();
        }
        PairRecord p = l.pairs().get(WeeklyGrid.pairKey(itemNumber, storeCode));
        if (p == null) {
            return Optional.empty();
        }
        WeeklyGrid.Context c = new WeeklyGrid.Context(p.item(), p.store(), p.category(), LocalDate.parse(p.lastWeek()),
                p.lastPrice(), p.cost(), p.compMedian(), p.lags(), p.medianPrice(), p.weeks(), p.units());
        boolean itemOneHot = DemandTrainer.Settings.defaults().itemOneHot();
        boolean season = DemandTrainer.trainedWithSeason(l.forecast());
        double scale = p.calibration() > 0 ? p.calibration() : 1;
        double units = scale * DemandTrainer.predictUnits(l.forecast(),
                Features.at(c, price.doubleValue(), itemOneHot, season, true));
        double settled = DemandTrainer.settledUnits(l.response(), c, price.doubleValue(), itemOneHot, season);
        double[] flat = new double[DemandTrainer.HORIZON_WEEKS];
        java.util.Arrays.fill(flat, price.doubleValue());
        DemandTrainer.Verdict v = verdict(p, l.forecastModel());
        // The forest answers at the price asked; every other forecaster holds the one level it gave at training.
        double horizon = Smoothers.FOREST.equals(v.method())
                ? DemandTrainer.rollForward(l.forecast(), c, c.lags(), c.compMedian(), flat,
                        c.lastWeek().plusWeeks(1), scale, itemOneHot, season)
                : v.nextUnits();
        return Optional.of(new Forecast(dec(units, 1), dec(settled, 1), dec(p.elasticity(), 2), p.usable(), p.beats(),
                p.weeks(), pct(p.errorModel()), pct(p.errorBaseline()), l.trainedAt(), p.note(), dec(horizon, 1),
                dec(p.trailingUnits(), 1), v.usable(), pct(finite(v.errorShare())), pct(finite(v.naiveShare())),
                Smoothers.label(v.method()), v.note(), v.proven()));
    }

    @Override
    public boolean trained() {
        return load(TenantContext.requireTenantId()) != null;
    }

    private Loaded load(UUID tenantId) {
        return loaded.get(tenantId, t -> repo.findById(t)
                .filter(r -> r.getModel() != null && r.getResponseModel() != null)
                .map(r -> new Loaded(r.getTrainedAt(), deserialize(r.getModel()), deserialize(r.getResponseModel()),
                        r.getPairs(), chosenModel(r.getForecastModel())))
                .orElse(null));
    }

    static Model<Regressor> deserialize(byte[] bytes) {
        try {
            @SuppressWarnings("unchecked")
            Model<Regressor> model = (Model<Regressor>) Model.deserialize(ModelProto.parseFrom(bytes));
            return model;
        } catch (InvalidProtocolBufferException ex) {
            throw new IllegalStateException("The stored demand model could not be read", ex);
        }
    }

    /** The seats that may change the pricing model are the ones that may train this one and choose what it reads. */
    private void requireGuardrailSeat(TenantContext.Actor actor, String what) {
        Persona persona = policy.personaFor(actor.tenantId(), actor.role());
        if (!persona.guardrails()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed",
                    "Your seat cannot " + what + ". Heads of sales and purchasing, finance and the "
                            + "commercial director can.");
        }
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static BigDecimal dec(double v, int scale) {
        return Double.isFinite(v) ? BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP) : null;
    }

    /** A share of units as a percentage with one decimal; null when there was too little to score. */
    private static BigDecimal pct(Double share) {
        return share == null ? null : dec(share * 100, 1);
    }
}
