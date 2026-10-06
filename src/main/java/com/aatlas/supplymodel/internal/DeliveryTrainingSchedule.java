package com.aatlas.supplymodel.internal;

import com.aatlas.common.error.ApiException;
import com.aatlas.common.schedule.Recurrence;
import com.aatlas.common.schedule.TrainingScheduleDtos;
import com.aatlas.common.schedule.TrainingSchedules;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.supplymodel.DeliveryModels;
import com.aatlas.policy.PolicyReader;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * When the delivery model retrains: daily, weekly, monthly or only on request, at the tenant's own
 * time - and, if the tenant wants, as soon as enough new received purchase orders are waiting.
 * A tenant that never chose keeps the old nightly run (05:30 UTC).
 */
@Service
class DeliveryTrainingSchedule {

    private static final Logger log = LoggerFactory.getLogger(DeliveryTrainingSchedule.class);

    static final String MODEL = "delivery";
    static final Recurrence DEFAULT = new Recurrence(Recurrence.DAILY, LocalTime.of(5, 30), null, null, ZoneId.of("UTC"));
    private static final String ROWS = "received orders";

    private final TrainingSchedules schedules;
    private final DeliveryModelService service;
    private final PolicyReader policy;
    private final AatlasClock clock;

    DeliveryTrainingSchedule(TrainingSchedules schedules, DeliveryModelService service, PolicyReader policy,
            AatlasClock clock) {
        this.schedules = schedules;
        this.service = service;
        this.policy = policy;
        this.clock = clock;
    }

    TrainingScheduleDtos.View get() {
        UUID tenant = TenantContext.requireTenantId();
        TrainingSchedules.Schedule s = schedules.get(tenant, MODEL, DEFAULT);
        return TrainingScheduleDtos.view(s, 0, ROWS, clock.now());
    }

    TrainingScheduleDtos.View save(TrainingScheduleDtos.Request req) {
        TenantContext.Actor actor = TenantContext.current()
                .orElseThrow(() -> ApiException.forbidden("Sign in to change when the delivery model trains."));
        if (!policy.personaFor(actor.tenantId(), actor.role()).guardrails()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "not_allowed", "Your seat cannot change when the delivery "
                    + "model trains. Heads of sales and purchasing, finance and the commercial director can.");
        }
        try {
            schedules.save(actor.tenantId(), MODEL, TrainingScheduleDtos.recurrence(req),
                    false, TrainingScheduleDtos.minNewRows(req), clock.now(),
                    actor.userId());
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("validation_failed", ex.getMessage());
        }
        return get();
    }

    /**
     * The tick for one tenant (bound by the caller): trains when the schedule says so, or when enough new
     * received orders are waiting and the tenant asked for that. A tenant seen for the first time gets its default
     * row and waits for the next scheduled time.
     *
     * @return whether a training run happened
     */
    boolean tick(UUID tenant) {
        Instant now = clock.now();
        TrainingSchedules.Schedule s = schedules.get(tenant, MODEL, DEFAULT);
        if (!s.stored()) {
            schedules.seed(tenant, MODEL, DEFAULT, now);
            return false;
        }
        // Time only: retraining on new data is not offered, so nothing waiting is counted.
        TrainingSchedules.Due due = TrainingSchedules.due(s, now, 0);
        if (!due.due()) {
            return false;
        }
        // A run on new data leaves the next scheduled time where it was.
        Instant next = s.nextRunAt() != null && s.nextRunAt().isAfter(now) ? s.nextRunAt()
                : s.recurrence().next(now);
        if (!schedules.claim(tenant, MODEL, s, next, now)) {
            return false;
        }
        try {
            DeliveryModels.Status status = service.train(tenant);
            schedules.finished(tenant, MODEL, status.trained() ? "trained" : "not-trained",
                    ("new-data".equals(due.why()) ? "New received orders came in. " : "") + status.note());
            return status.trained();
        } catch (RuntimeException ex) {
            log.warn("Delivery model for tenant {} could not be trained: {}", tenant, ex.getMessage());
            schedules.finished(tenant, MODEL, "failed", ex.getMessage());
            return false;
        }
    }

}
