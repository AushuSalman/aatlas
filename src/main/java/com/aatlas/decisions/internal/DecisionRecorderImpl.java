package com.aatlas.decisions.internal;

import com.aatlas.analytics.ProcurementLedger;
import com.aatlas.analytics.RecordAward;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.common.web.CursorPage;
import com.aatlas.decisions.DealRecord;
import com.aatlas.decisions.Decision;
import com.aatlas.decisions.DecisionRecorder;
import com.aatlas.decisions.DecisionStatus;
import com.aatlas.decisions.QuoteBreakdown;
import com.aatlas.decisions.RecordDecisionRequest;
import com.aatlas.decisions.RecordPurchaseRequest;
import com.aatlas.decisions.RecordSaleRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what a user actually did. Ports {@code intel/decisions.ts}'s {@code recordDecision}/
 * {@code recordPurchase} (and, as {@link #recordSale}, {@code platform/recorded.ts}'s
 * {@code recordSale} - see {@link DecisionRecorder} for why the two-file frontend split
 * becomes one linked write here).
 */
@Service
class DecisionRecorderImpl implements DecisionRecorder {

    private static final BigDecimal ROUNDING_EPSILON = new BigDecimal("0.005");

    private final DecisionRepository decisions;
    private final DealRepository deals;
    private final QuoteRepository quotes;
    private final ProcurementLedger ledger;
    private final AatlasClock clock;

    DecisionRecorderImpl(DecisionRepository decisions, DealRepository deals, QuoteRepository quotes,
            ProcurementLedger ledger, AatlasClock clock) {
        this.decisions = decisions;
        this.deals = deals;
        this.quotes = quotes;
        this.ledger = ledger;
        this.clock = clock;
    }

    private static double round2(double n) {
        return Math.round(n * 100.0) / 100.0;
    }

    private static BigDecimal bd(double n) {
        return BigDecimal.valueOf(n);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Mirrors {@code recordSale}/{@code recordPurchase}'s own id generation: {@code rec-<epoch ms>-<0..999>}. */
    private static String dealKey(String prefix) {
        return prefix + "-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000);
    }

    @Override
    @Transactional
    public Decision record(RecordDecisionRequest request) {
        return save(request, DecisionStatus.APPLIED);
    }

    @Override
    @Transactional
    public Decision recordPending(RecordDecisionRequest request) {
        return save(request, DecisionStatus.PENDING);
    }

    private Decision save(RecordDecisionRequest request, DecisionStatus status) {
        UUID userId = TenantContext.currentUserId()
                .orElseThrow(() -> ApiException.forbidden("A decision must be recorded by a signed-in user."));

        DecisionEntity entity = new DecisionEntity(userId, request.kind(), request.title(), request.itemNumber(),
                request.scope() == null ? "" : request.scope(), nz(request.recommended()), nz(request.applied()),
                nz(request.expectedImpact()), request.impactLabel() == null ? "" : request.impactLabel(),
                request.detail() == null ? "" : request.detail(), request.count(), status);
        entity = decisions.save(entity);

        if (request.quote() != null) {
            QuoteBreakdown q = request.quote();
            quotes.save(new QuoteEntity(entity.getId(), q));
        }
        return Mappers.toDecision(entity);
    }

    @Override
    @Transactional
    public Decision resolve(UUID decisionId, DecisionStatus status) {
        UUID tenantId = TenantContext.requireTenantId();
        DecisionEntity entity = decisions.findByTenantIdAndId(tenantId, decisionId)
                .orElseThrow(() -> ApiException.notFound("Decision", decisionId));
        entity.setStatus(DecisionEntity.Status.valueOf(status.wire().replace('-', '_')));
        return Mappers.toDecision(decisions.save(entity));
    }

    @Override
    @Transactional
    public DealRecord recordSale(RecordSaleRequest r) {
        boolean followed = r.actualPrice().compareTo(r.suggestedPrice()) >= 0;
        double gain = followed
                ? round2(r.actualPrice().subtract(r.baselinePrice()).doubleValue() * r.qty())
                : 0;
        double lost = followed
                ? 0
                : round2(Math.max(0, r.suggestedPrice().subtract(r.actualPrice()).doubleValue()) * r.qty());
        boolean belowFloor = r.marginFloor() != null
                && r.actualPrice().compareTo(r.marginFloor().subtract(ROUNDING_EPSILON)) < 0;

        LocalDate date = r.date() != null ? r.date() : clock.today();
        Instant now = clock.now();

        DealEntity entity = new DealEntity(dealKey("rec"), "sell", r.itemNumber(), r.description(), r.storeName(),
                r.qty(), r.cost(), r.baselinePrice(), r.suggestedPrice(), r.actualPrice(), followed,
                bd(gain), bd(lost), true, r.customerName(), now, belowFloor, null, date, r.decisionId());
        entity = deals.save(entity);
        return Mappers.toDealRecord(entity);
    }

    @Override
    @Transactional
    public DealRecord recordPurchase(RecordPurchaseRequest r) {
        boolean followed = r.followed() != null
                ? r.followed()
                : r.agreedCost().compareTo(r.targetCost().add(ROUNDING_EPSILON)) <= 0;
        // A followed buy that costs more than the old price bought fulfilment, not a saving -
        // it counts as zero rather than as money lost.
        double gain = followed
                ? round2(Math.max(0, r.cost().subtract(r.agreedCost()).doubleValue()) * r.qty())
                : 0;
        double lost = followed
                ? 0
                : round2(Math.max(0, r.agreedCost().subtract(r.targetCost()).doubleValue()) * r.qty());

        LocalDate date = r.date() != null ? r.date() : clock.today();
        Instant now = clock.now();

        DealEntity entity = new DealEntity(dealKey("recb"), "buy", r.itemNumber(), r.description(), r.supplierName(),
                r.qty(), r.cost(), r.cost(), r.targetCost(), r.agreedCost(), followed, bd(gain), bd(lost), true, null,
                now, null, r.destinationId(), date, r.decisionId());
        entity = deals.save(entity);

        if (r.supplierId() != null && !r.supplierId().isBlank()
                && r.destinationId() != null && !r.destinationId().isBlank()) {
            BigDecimal unit = r.agreedCost().setScale(4, RoundingMode.HALF_UP);
            ledger.recordAward(new RecordAward(
                    r.itemNumber(), r.description(), r.category(), r.supplierId(), r.supplierName(),
                    r.country() == null ? "USA" : r.country(), r.destinationId(), r.qty(),
                    unit, BigDecimal.ZERO, BigDecimal.ZERO, r.cost(), r.targetCost(), date, r.decisionId()));
        }
        return Mappers.toDealRecord(entity);
    }

    @Override
    public Decision get(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        return decisions.findByTenantIdAndId(tenantId, id)
                .map(Mappers::toDecision)
                .orElseThrow(() -> ApiException.notFound("Decision", id));
    }

    /** The deals recorded against one decision - {@code GET /decisions/{id}}'s second half. Package-visible so the controller reaches it through the service, not the repository (see {@code ArchitectureRulesTest}). */
    java.util.List<DealRecord> dealsFor(UUID decisionId) {
        UUID tenantId = TenantContext.requireTenantId();
        return deals.findByTenantIdAndDecisionId(tenantId, decisionId).stream().map(Mappers::toDealRecord).toList();
    }

    /** {@code DELETE /decisions/mine}: this user's own decision rows. Deals already recorded are untouched. */
    @Transactional
    int clearMine() {
        UUID tenantId = TenantContext.requireTenantId();
        UUID userId = TenantContext.currentUserId()
                .orElseThrow(() -> ApiException.forbidden("Clearing decisions requires a signed-in user."));
        return decisions.deleteByTenantIdAndUserId(tenantId, userId);
    }

    @Override
    public CursorPage<Decision> list(int limit, String cursor) {
        UUID tenantId = TenantContext.requireTenantId();
        Instant after = decodeCursor(cursor);

        // A Specification, not a JPQL "(:cursor is null or ...)" - the PostgreSQL JDBC driver
        // cannot infer a bind parameter's type when it appears only inside an IS NULL branch
        // ("could not determine data type of parameter"), so the predicate is added only when
        // there is a cursor, exactly like CatalogService's keyset reads.
        org.springframework.data.jpa.domain.Specification<DecisionEntity> spec = (root, query, cb) -> {
            var tenantPredicate = cb.equal(root.get("tenantId"), tenantId);
            if (after == null) {
                return tenantPredicate;
            }
            return cb.and(tenantPredicate, cb.lessThan(root.get("createdAt"), after));
        };
        List<DecisionEntity> page = decisions.findBy(spec, fetch -> fetch
                .sortBy(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                .limit(limit + 1)
                .all());
        return CursorPage.of(page.stream().map(Mappers::toDecision).toList(), limit,
                d -> encodeCursor(d.at()));
    }

    private static String encodeCursor(Instant at) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(at.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Instant decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw ApiException.badRequest("invalid_cursor", "That cursor is not one this API issued. Start again without a cursor.");
        }
    }
}
