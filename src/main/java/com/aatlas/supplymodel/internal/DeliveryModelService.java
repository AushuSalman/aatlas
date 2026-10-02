package com.aatlas.supplymodel.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.history.HistoryCaches;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import com.aatlas.supplymodel.DeliveryModels;
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
 * Trains, stores and serves a tenant's delivery models.
 *
 * <p>A run loads the received orders of the last three years, builds the grid, fits and scores
 * the two forests, and writes the model bytes and the per-supplier report to the tenant's row.
 * A run that cannot fit (too few orders) writes the reason and drops any earlier models, so
 * stale ones never serve. Served models are deserialised once per tenant and kept in a small
 * cache; a run evicts its tenant.
 */
@Service
class DeliveryModelService implements DeliveryModels {

    /** Years of orders a run loads. */
    static final int YEARS_BACK = 3;

    private static final Logger log = LoggerFactory.getLogger(DeliveryModelService.class);

    record Loaded(Instant trainedAt, Model<Regressor> slip, Model<Regressor> late, Map<String, SupplierRecord> suppliers) {
    }

    private final DeliveryModelRepository repo;
    private final OrderRows orders;
    private final PolicyReader policy;
    private final HistoryCaches caches;
    private final AatlasClock clock;
    private final int trees;
    private final double holdoutShare;
    private final int minHoldout;
    private final Cache<UUID, Loaded> loaded = Caffeine.newBuilder()
            .maximumSize(32)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    DeliveryModelService(DeliveryModelRepository repo, OrderRows orders, PolicyReader policy, HistoryCaches caches,
            AatlasClock clock,
            @Value("${aatlas.delivery-model.trees:100}") int trees,
            @Value("${aatlas.delivery-model.holdout-share:0.2}") double holdoutShare,
            @Value("${aatlas.delivery-model.min-holdout:8}") int minHoldout) {
        this.repo = repo;
        this.orders = orders;
        this.policy = policy;
        this.caches = caches;
        this.clock = clock;
        this.trees = trees;
        this.holdoutShare = holdoutShare;
        this.minHoldout = minHoldout;
    }

    @Override
    public Status train() {
        TenantContext.Actor actor = TenantContext.current()
                .orElseThrow(() -> ApiException.forbidden("Sign in to train the delivery model."));
        requireMayTrain(actor);
        return train(actor.tenantId());
    }

    /** One run for one tenant, as the nightly job and the on-demand call share it. Runs as the tenant. */
    @Transactional
    public Status train(UUID tenantId) {
        LocalDate today = clock.today();
        Instant now = clock.now();
        List<OrderGrid.Order> received = orders.received(tenantId, today.minusYears(YEARS_BACK));
        OrderGrid.Grid grid = OrderGrid.build(received);
        DeliveryModelEntity row = repo.findById(tenantId).orElseGet(() -> DeliveryModelEntity.fresh(tenantId));
        try {
            DeliveryTrainer.Settings settings = new DeliveryTrainer.Settings(trees, 8, 3, 0.6, holdoutShare, minHoldout,
                    42L);
            DeliveryTrainer.Result result = DeliveryTrainer.train(grid, settings);
            Map<String, SupplierRecord> suppliers = new LinkedHashMap<>();
            for (Map.Entry<String, OrderGrid.Context> e : grid.contexts().entrySet()) {
                suppliers.put(e.getKey(), record(e.getValue(), result.evals().get(e.getKey())));
            }
            long usable = suppliers.values().stream().filter(SupplierRecord::usable).count();
            String note = usable + " of " + suppliers.size()
                    + " suppliers: the model beat the supplier's own record on the held-out orders";
            row.trained(result.slip().serialize().toByteArray(), result.late().serialize().toByteArray(), suppliers,
                    now, result.rows(), grid.firstOrder(), grid.lastOrder(), result.holdoutRows(), result.millis(),
                    note);
            log.info("Delivery model for tenant {}: {} orders, {} suppliers, {} usable, {} ms", tenantId,
                    result.rows(), suppliers.size(), usable, result.millis());
        } catch (IllegalStateException ex) {
            row.untrained("Not enough received purchase orders to train: " + ex.getMessage(), now,
                    grid.rows().size(), grid.firstOrder(), grid.lastOrder());
            log.info("Delivery model for tenant {} not trained: {}", tenantId, ex.getMessage());
        }
        repo.save(row);
        loaded.invalidate(tenantId);
        // The buy chain reads this model: supplier comparisons cached before the run are stale now.
        caches.evictAfterCommit(tenantId);
        return status(row);
    }

    private static SupplierRecord record(OrderGrid.Context c, DeliveryTrainer.SupplierEval ev) {
        return new SupplierRecord(c.supplierId(), c.supplierName(), c.country(), c.orders(), c.late(), c.lateRate(),
                c.meanSlip(), c.meanActual(), c.meanPromised(), c.meanQty(),
                c.lastOrder() == null ? null : c.lastOrder().toString(), c.promisedByItem(), ev.holdoutOrders(),
                finite(ev.maeModel()), finite(ev.maeBaseline()), finite(ev.brierModel()), finite(ev.brierBaseline()),
                ev.beatsLead(), ev.beatsLate(), ev.usable(), ev.predictedSlip(), ev.predictedLateProb(), ev.note());
    }

    @Override
    @Transactional(readOnly = true)
    public Status status() {
        UUID tenantId = TenantContext.requireTenantId();
        return repo.findById(tenantId).map(this::status).orElse(new Status(false, null, 0, 0, 0, null, null, 0, 0,
                "Not trained yet. It runs nightly once there are received purchase orders, or train it now.",
                0, List.of()));
    }

    private Status status(DeliveryModelEntity row) {
        List<SupplierReport> report = row.getSuppliers().values().stream()
                .sorted(Comparator.comparing(SupplierRecord::usable).reversed()
                        .thenComparing(SupplierRecord::orders, Comparator.reverseOrder()))
                .map(DeliveryModelService::report)
                .toList();
        int usable = (int) row.getSuppliers().values().stream().filter(SupplierRecord::usable).count();
        return new Status(row.getSlipModel() != null, row.getTrainedAt(), row.getOrderCount(), row.getSupplierCount(),
                usable, row.getFromDate(), row.getToDate(), row.getHoldoutOrders(), row.getTrainMillis(),
                row.getNote(), orders.receivedSince(row.getTenantId(), row.getTrainedAt()), report);
    }

    static SupplierReport report(SupplierRecord s) {
        return new SupplierReport(s.supplierId(), s.name(), s.country(), s.orders(), s.late(),
                dec(s.lateRate() * 100, 0), dec(s.meanPromised(), 1), dec(s.meanActual(), 1), s.holdoutOrders(),
                dec(s.maeModel(), 2), dec(s.maeBaseline(), 2), dec(s.brierModel(), 3), dec(s.brierBaseline(), 3),
                s.beatsLead(), s.beatsLate(), s.usable(), dec(s.meanPromised() + s.predictedSlip(), 1),
                dec(s.predictedLateProb(), 2), s.note());
    }

    @Override
    public Optional<Forecast> forecast(String supplier, String itemNumber, String storeCode, int qty,
            Integer promisedDays) {
        if (supplier == null || supplier.isBlank()) {
            return Optional.empty();
        }
        UUID tenantId = TenantContext.requireTenantId();
        Loaded l = load(tenantId);
        if (l == null) {
            return Optional.empty();
        }
        // By the supplier master's id first, else by name: the Buy screens hold both.
        SupplierRecord s = l.suppliers().get(supplier.trim());
        if (s == null) {
            s = l.suppliers().values().stream()
                    .filter(r -> r.name() != null && r.name().equalsIgnoreCase(supplier.trim()))
                    .findFirst().orElse(null);
        }
        if (s == null) {
            return Optional.empty();
        }
        double promised = promisedDays != null && promisedDays > 0 ? promisedDays
                : s.promisedByItem() != null && itemNumber != null && s.promisedByItem().containsKey(itemNumber)
                        ? s.promisedByItem().get(itemNumber)
                        : s.meanPromised();
        String category = itemNumber == null ? null : orders.categoryOf(tenantId, itemNumber).orElse(null);
        boolean season = DeliveryTrainer.trainedWithSeason(l.slip());
        OrderGrid.Context c = new OrderGrid.Context(s.supplierId(), s.name(), s.country(), s.orders(), s.late(),
                s.lateRate(), s.meanSlip(), s.meanActual(), s.meanPromised(), s.meanQty(),
                s.lastOrder() == null ? null : LocalDate.parse(s.lastOrder()), s.promisedByItem());
        // No quantity given means a typical order: the supplier's usual quantity.
        double units = qty > 0 ? qty : Math.max(1, s.meanQty());
        DeliveryFeatures.Input in = DeliveryFeatures.at(c, category, storeCode, units, promised, clock.today(), season);
        double slip = DeliveryTrainer.predict(l.slip(), in);
        double late = DeliveryTrainer.clamp01(DeliveryTrainer.predict(l.late(), in));
        return Optional.of(new Forecast(dec(Math.max(0, promised + slip), 1), dec(late, 2),
                dec(Math.max(0, slip), 1), dec(promised, 1), s.usable(), s.orders(), dec(s.maeModel(), 2),
                dec(s.maeBaseline(), 2), dec(s.brierModel(), 3), dec(s.brierBaseline(), 3), l.trainedAt(), s.note()));
    }

    private Loaded load(UUID tenantId) {
        return loaded.get(tenantId, t -> repo.findById(t)
                .filter(r -> r.getSlipModel() != null && r.getLateModel() != null)
                .map(r -> new Loaded(r.getTrainedAt(), deserialize(r.getSlipModel()), deserialize(r.getLateModel()),
                        r.getSuppliers()))
                .orElse(null));
    }

    static Model<Regressor> deserialize(byte[] bytes) {
        try {
            @SuppressWarnings("unchecked")
            Model<Regressor> model = (Model<Regressor>) Model.deserialize(ModelProto.parseFrom(bytes));
            return model;
        } catch (InvalidProtocolBufferException ex) {
            throw new IllegalStateException("The stored delivery model could not be read", ex);
        }
    }

    private void requireMayTrain(TenantContext.Actor actor) {
        Persona persona = policy.personaFor(actor.tenantId(), actor.role());
        if (!persona.guardrails()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed",
                    "Your seat cannot train the delivery model. Heads of sales and purchasing, finance and the "
                            + "commercial director can.");
        }
    }

    private static Double finite(double v) {
        return Double.isFinite(v) ? v : null;
    }

    private static BigDecimal dec(Double v, int scale) {
        return v == null || !Double.isFinite(v) ? null : BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }
}
