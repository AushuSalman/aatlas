package com.aatlas.rfq.internal;

import com.aatlas.approvals.ApprovalRequester;
import com.aatlas.approvals.RaiseApprovalRequest;
import com.aatlas.buy.BuyIntel;
import com.aatlas.buy.BuyIntelReader;
import com.aatlas.buy.ProcurementPlan;
import com.aatlas.buy.ProcurementPlanReader;
import com.aatlas.buy.ScoredSupplier;
import com.aatlas.common.error.ApiException;
import com.aatlas.common.tenant.TenantContext;
import com.aatlas.common.time.AatlasClock;
import com.aatlas.decisions.Decision;
import com.aatlas.decisions.DecisionKind;
import com.aatlas.decisions.DecisionRecorder;
import com.aatlas.decisions.RecordDecisionRequest;
import com.aatlas.decisions.RecordPurchaseRequest;
import com.aatlas.ingest.SampleDataProvisioner;
import com.aatlas.policy.Persona;
import com.aatlas.policy.PolicyReader;
import com.aatlas.rfq.Rfq;
import com.aatlas.rfq.RfqAwardResult;
import com.aatlas.rfq.RfqRecommendation;
import com.aatlas.rfq.RfqStatus;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The RFQ screens' seven endpoints: create a round, list and read them, edit while draft,
 * send, enter replies, recommend, award, export. Ports {@code intel/rfq.ts} - see {@link
 * RfqEngine} for the two pure functions - against the real {@code buy} panel and the real
 * {@code decisions}/{@code approvals} write path.
 */
@Service
class RfqService {

    private final RfqRepository rfqs;
    private final RfqInviteRepository invites;
    private final RfqQuoteRepository quotes;
    private final BuyIntelReader buyIntel;
    private final ProcurementPlanReader procurementPlan;
    private final PolicyReader policy;
    private final DecisionRecorder ledger;
    private final ApprovalRequester approvals;
    private final RfqMailer mailer;
    private final AatlasClock clock;
    private final SampleDataProvisioner sampleData;

    RfqService(RfqRepository rfqs, RfqInviteRepository invites, RfqQuoteRepository quotes, BuyIntelReader buyIntel,
            ProcurementPlanReader procurementPlan, PolicyReader policy, DecisionRecorder ledger,
            ApprovalRequester approvals, RfqMailer mailer, AatlasClock clock, SampleDataProvisioner sampleData) {
        this.rfqs = rfqs;
        this.invites = invites;
        this.quotes = quotes;
        this.buyIntel = buyIntel;
        this.procurementPlan = procurementPlan;
        this.policy = policy;
        this.ledger = ledger;
        this.approvals = approvals;
        this.mailer = mailer;
        this.clock = clock;
        this.sampleData = sampleData;
    }

    // -- create / read -----------------------------------------------------------------

    @Transactional
    Rfq create(CreateRfqRequest request) {
        BuyIntel intel = buyIntel.getBuyIntel(request.itemNumber(), request.regionKey(), request.qty(),
                request.destinationId());
        ProcurementPlan plan = procurementPlan.procurementPlan(intel, request.requiredDays(), request.priority(),
                null);

        Map<String, ScoredSupplier> ranked = new LinkedHashMap<>();
        for (ScoredSupplier r : plan.ranked()) {
            ranked.put(r.s().supplierId(), r);
        }
        List<ScoredSupplier> chosen = request.supplierIds().stream()
                .map(ranked::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (chosen.isEmpty()) {
            throw ApiException.badRequest("no_such_supplier",
                    "None of the given supplier ids are in this order's ranked panel.");
        }

        String ref = "RFQ-" + String.format("%06d", clock.now().toEpochMilli() % 1_000_000);
        String incoterm = request.incoterm() == null || request.incoterm().isBlank()
                ? "DDP, duty paid" : request.incoterm();
        String paymentTerms = request.paymentTerms() == null || request.paymentTerms().isBlank()
                ? "Net 45" : request.paymentTerms();
        String message = draftMessage(intel, plan, ref);

        UUID userId = TenantContext.currentUserId()
                .orElseThrow(() -> ApiException.forbidden("Creating an RFQ requires a signed-in user."));

        RfqEntity entity = new RfqEntity(ref, intel.itemNumber(), intel.name(), intel.regionKey(),
                intel.regionLabel(), intel.destinationId(), intel.destinationLabel(), request.qty(),
                request.requiredDays(), plan.urgency(), plan.priority(), incoterm, paymentTerms, request.notes(),
                message, userId);
        entity = rfqs.save(entity);

        for (ScoredSupplier r : chosen) {
            RfqInviteEntity invite = new RfqInviteEntity(entity.getId(), r.s().supplierId(), r.s().name(),
                    r.s().country(), r.route().label(), bd(r.route().unitCost()), r.route().days(),
                    bd(r.delivery().onTimePct()), r.risk().level());
            this.invites.save(invite);
        }

        return load(entity.getId());
    }

    private static String draftMessage(BuyIntel intel, ProcurementPlan plan, String ref) {
        return """
                Subject: RFQ %s - %s, %,d units

                Hello,

                Please quote %,d units of %s (%s), delivered to %s, required on site within %d %s of order.

                Please include:
                - Landed cost per unit (your price plus freight and duty to our dock)
                - Lead time from order to dock, and the route you would use
                - Quote validity
                - Minimum order quantity and payment terms

                Responses by end of day tomorrow, please. Reference %s in your reply.

                Thanks,"""
                .formatted(ref, intel.name(), intel.qty(), intel.qty(), intel.name(), intel.itemNumber(),
                        intel.destinationLabel(), plan.requiredDays(), plan.requiredDays() == 1 ? "day" : "days",
                        ref);
    }

    @Transactional(readOnly = true)
    List<Rfq> list(RfqStatus status, int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        var page = org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        List<RfqEntity> page1 = status == null
                ? rfqs.findByTenantIdOrderByCreatedAtDesc(tenantId, page)
                : rfqs.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId,
                        RfqEntity.Status.valueOf(status.wire()), page);
        return page1.stream().map(e -> toRfq(e, tenantId)).toList();
    }

    @Transactional(readOnly = true)
    Rfq get(UUID id) {
        return load(id);
    }

    private Rfq load(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        RfqEntity entity = rfqs.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Rfq", id));
        return toRfq(entity, tenantId);
    }

    private Rfq toRfq(RfqEntity entity, UUID tenantId) {
        List<RfqInviteEntity> inv = invites.findByTenantIdAndRfqId(tenantId, entity.getId());
        List<RfqQuoteEntity> qs = quotes.findByTenantIdAndRfqId(tenantId, entity.getId());
        return Mappers.toRfq(entity, inv, qs);
    }

    private RfqEntity mustBeDraft(UUID id, UUID tenantId) {
        RfqEntity entity = rfqs.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Rfq", id));
        if (entity.getStatus() != RfqEntity.Status.draft) {
            throw ApiException.conflict("not_draft", "RFQ " + entity.getRef() + " is " + entity.getStatus()
                    + "; only a draft round can be edited or (re)sent.");
        }
        return entity;
    }

    // -- edit / send ---------------------------------------------------------------------

    @Transactional
    Rfq update(UUID id, UpdateRfqRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        RfqEntity entity = mustBeDraft(id, tenantId);
        if (request.qty() != null && request.qty() > 0) {
            entity.setQty(request.qty());
        }
        if (request.requiredDays() != null && request.requiredDays() > 0) {
            entity.setRequiredDays(request.requiredDays());
        }
        if (request.priority() != null && !request.priority().isBlank()) {
            entity.setPriority(request.priority());
        }
        if (request.notes() != null) {
            entity.setNotes(request.notes());
        }
        return toRfq(rfqs.save(entity), tenantId);
    }

    @Transactional
    Rfq send(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        RfqEntity entity = mustBeDraft(id, tenantId);
        Instant now = clock.now();
        entity.setStatus(RfqEntity.Status.sent);
        entity.setClosesAt(now.plus(java.time.Duration.ofDays(2)));
        rfqs.save(entity);

        for (RfqInviteEntity invite : invites.findByTenantIdAndRfqId(tenantId, id)) {
            invite.markSent(now);
            invites.save(invite);
            mailer.send(invite.getName(), entity.getRef(), entity.getMessage());
        }
        return toRfq(entity, tenantId);
    }

    // -- quotes ----------------------------------------------------------------------------

    @Transactional
    Rfq enterQuotes(UUID id, EnterQuotesRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        RfqEntity entity = rfqs.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Rfq", id));
        if (entity.getStatus() != RfqEntity.Status.sent && entity.getStatus() != RfqEntity.Status.quoted) {
            throw ApiException.conflict("not_sent",
                    "RFQ " + entity.getRef() + " is " + entity.getStatus() + "; send it before entering replies.");
        }

        Map<String, EnterQuotesRequest.Reply> manual = new LinkedHashMap<>();
        if (request.replies() != null) {
            for (EnterQuotesRequest.Reply r : request.replies()) {
                manual.put(r.supplierId(), r);
            }
        }
        UUID userId = TenantContext.currentUserId().orElse(null);
        Instant now = clock.now();
        LocalDate today = clock.today();
        // Spec-A S2 "rfq": RfqEngine.simulate is the one place left that may call Seeded, and
        // only to stand in for a reply on the SAMPLE tenant's own demo data - never for a
        // tenant with real uploaded data, where an un-replied invite must simply stay
        // `invited` until someone types a real quote in.
        boolean sampleTenant = sampleData.current(tenantId).map(v -> "sample".equals(v.kind())).orElse(false);
        boolean anyRecorded = false;

        for (RfqInviteEntity invite : invites.findByTenantIdAndRfqId(tenantId, id)) {
            if (quotes.findByTenantIdAndRfqIdAndSupplierId(tenantId, id, invite.getSupplierId()).isPresent()) {
                continue;
            }
            EnterQuotesRequest.Reply typed = manual.get(invite.getSupplierId());
            RfqQuoteEntity quote;
            if (typed != null) {
                BigDecimal landed = typed.quotedLanded() != null ? typed.quotedLanded() : typed.quotedUnit();
                BigDecimal unit = typed.quotedUnit() != null ? typed.quotedUnit() : landed;
                LocalDate validUntil = typed.validDays() != null ? today.plusDays(typed.validDays()) : null;
                BigDecimal vsExpected = !typed.declined() && landed != null
                        ? landed.subtract(invite.getExpectedLanded())
                                .divide(invite.getExpectedLanded(), 4, RoundingMode.HALF_UP)
                                .movePointRight(2)
                        : null;
                quote = new RfqQuoteEntity(id, invite.getSupplierId(), invite.getName(), typed.declined(),
                        typed.declined() ? null : unit, typed.declined() ? null : landed, "USD",
                        typed.declined() ? null : typed.leadDays(), typed.declined() ? null : validUntil,
                        typed.paymentTerms(), typed.note(), vsExpected, now, userId, false);
            } else if (sampleTenant) {
                RfqEngine.SimulatedQuote sim = RfqEngine.simulate(entity.getItemNumber(), entity.getQty(),
                        entity.getRequiredDays(), invite);
                LocalDate validUntil = today.plusDays(sim.validDays());
                quote = new RfqQuoteEntity(id, invite.getSupplierId(), invite.getName(), sim.declined(),
                        sim.declined() ? null : sim.quoted(), sim.declined() ? null : sim.quoted(), "USD",
                        sim.declined() ? null : sim.leadDays(), sim.declined() ? null : validUntil, null,
                        sim.note(), sim.declined() ? null : BigDecimal.valueOf(sim.vsExpectedPct()), now, null, true);
            } else {
                // A tenant on real data: nobody has replied yet. The invite stays `invited`
                // and no RfqQuote row is written - never a simulated stand-in presented as a
                // real supplier's answer.
                continue;
            }
            quotes.save(quote);
            invite.setStatus(quote.isDeclined() ? RfqInviteEntity.Status.declined : RfqInviteEntity.Status.quoted);
            invites.save(invite);
            anyRecorded = true;
        }

        if (anyRecorded && entity.getStatus() == RfqEntity.Status.sent) {
            entity.setStatus(RfqEntity.Status.quoted);
            rfqs.save(entity);
        }
        return toRfq(entity, tenantId);
    }

    // -- recommendation ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    RfqRecommendation recommendation(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        RfqEntity entity = rfqs.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Rfq", id));

        Map<String, BigDecimal> live = new LinkedHashMap<>();
        Map<String, Double> onTime = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (RfqQuoteEntity q : quotes.findByTenantIdAndRfqId(tenantId, id)) {
            if (!q.isDeclined() && q.getQuotedLanded() != null) {
                live.put(q.getSupplierId(), q.getQuotedLanded());
                names.put(q.getSupplierId(), q.getName());
            }
        }
        for (RfqInviteEntity invite : invites.findByTenantIdAndRfqId(tenantId, id)) {
            onTime.put(invite.getSupplierId(), invite.getOnTimePct().doubleValue());
        }

        BuyIntel intel = buyIntel.getBuyIntel(entity.getItemNumber(), entity.getRegionKey(), entity.getQty(),
                entity.getDestinationId());
        ProcurementPlan plan = procurementPlan.procurementPlan(intel, entity.getRequiredDays(), entity.getPriority(),
                null);

        Optional<RfqEngine.Recommendation> rec = RfqEngine.recommend(live, onTime, names, entity.getRequiredDays(),
                plan.weights(), plan.ranked());
        RfqEngine.Recommendation r = rec.orElseThrow(() -> ApiException.conflict("no_live_quotes",
                "RFQ " + entity.getRef() + " has no live (non-declined) quotes to recommend from yet."));
        return new RfqRecommendation(r.supplierId(), names.get(r.supplierId()), r.reason(), r.scores());
    }

    // -- award -------------------------------------------------------------------------------

    @Transactional
    RfqAwardResult award(UUID id, AwardRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        RfqEntity entity = rfqs.findByTenantIdAndId(tenantId, id)
                .orElseThrow(() -> ApiException.notFound("Rfq", id));
        if (entity.getStatus() == RfqEntity.Status.awarded || entity.getStatus() == RfqEntity.Status.closed) {
            throw ApiException.conflict("already_awarded", "RFQ " + entity.getRef() + " is already "
                    + entity.getStatus() + ".");
        }
        RfqQuoteEntity quote = quotes.findByTenantIdAndRfqIdAndSupplierId(tenantId, id, request.supplierId())
                .filter(q -> !q.isDeclined() && q.getQuotedLanded() != null)
                .orElseThrow(() -> ApiException.badRequest("no_live_quote",
                        "Supplier " + request.supplierId() + " has no live quote on this round."));
        RfqInviteEntity invite = invites.findByTenantIdAndRfqId(tenantId, id).stream()
                .filter(i -> i.getSupplierId().equals(request.supplierId()))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("RfqInvite", request.supplierId()));

        BigDecimal orderValue = quote.getQuotedLanded().multiply(BigDecimal.valueOf(entity.getQty()));
        String role = TenantContext.current().map(TenantContext.Actor::role)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated",
                        "No seat bound to this request."));
        Persona persona = policy.personaFor(tenantId, role);

        RecordDecisionRequest decisionRequest = new RecordDecisionRequest(DecisionKind.BUY,
                entity.getItemName() + " awarded to " + quote.getName() + " via RFQ " + entity.getRef(),
                entity.getItemNumber(), entity.getRegionKey(), invite.getExpectedLanded(), quote.getQuotedLanded(),
                orderValue, "one-time order",
                "Awarded to " + quote.getName() + " via RFQ " + entity.getRef() + " at " + entity.getDestinationLabel(),
                entity.getQty(), null);

        if (persona.canApprove(orderValue)) {
            Decision decision = ledger.record(decisionRequest);
            recordPurchase(entity, quote, invite, decision.id());
            entity.setDecisionId(decision.id());
            entity.award(request.supplierId(), clock.now());
            rfqs.save(entity);
            return RfqAwardResult.approved();
        }

        Decision decision = ledger.recordPending(decisionRequest);
        entity.setDecisionId(decision.id());
        entity.award(request.supplierId(), clock.now());
        rfqs.save(entity);
        approvals.raise(new RaiseApprovalRequest(decision.id(), approverRoleKeyFor(persona, tenantId), orderValue,
                "RFQ " + entity.getRef() + ": " + entity.getItemName() + " to " + quote.getName()));
        return RfqAwardResult.pending(persona.approveLimit(), persona.approver());
    }

    /**
     * {@link Persona#approver()} is a display title ("Head of purchasing"), not the seat key
     * {@code approvals}' inbox filters by ({@code TenantContext.Actor#role}) - {@code
     * approval_request.approver_role} has to be the latter for {@code GET /approvals} to ever
     * find a request under "pending for my role". Resolved by matching the title against
     * {@code policy}'s own persona list; falls back to the title itself if, somehow, no seat
     * carries it (defensive - every approver named in {@code seed/personas.json} is itself a
     * persona title today).
     */
    private String approverRoleKeyFor(Persona persona, UUID tenantId) {
        if (persona.approver() == null) {
            return null;
        }
        return policy.personasFor(tenantId).stream()
                .filter(p -> persona.approver().equals(p.title()))
                .map(Persona::key)
                .findFirst()
                .orElse(persona.approver());
    }

    // -- approval outcomes ---------------------------------------------------------------------
    //
    // Called by ApprovalOutcomeListener with TenantContext already bound to the granting
    // event's tenant (see its javadoc for why a listener thread needs that explicitly).

    /**
     * {@code approvals.ApprovalGranted}: finish the purchase the award only provisionally
     * recorded, then move the decision on from {@code approved} (an approvals-module
     * milestone: signed off, not yet committed) to {@code applied} - the same terminal state
     * an award within limit reaches directly, so a screen reading {@code decisions} never
     * needs to know which path a purchase took.
     */
    @Transactional
    void onApprovalGranted(UUID decisionId) {
        UUID tenantId = TenantContext.requireTenantId();
        rfqs.findByTenantIdAndDecisionId(tenantId, decisionId).ifPresent(entity -> {
            RfqQuoteEntity quote = quotes
                    .findByTenantIdAndRfqIdAndSupplierId(tenantId, entity.getId(), entity.getAwardedSupplierId())
                    .orElseThrow(() -> ApiException.notFound("RfqQuote", entity.getAwardedSupplierId()));
            RfqInviteEntity invite = invites.findByTenantIdAndRfqId(tenantId, entity.getId()).stream()
                    .filter(i -> i.getSupplierId().equals(entity.getAwardedSupplierId()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("RfqInvite", entity.getAwardedSupplierId()));
            recordPurchase(entity, quote, invite, decisionId);
            ledger.resolve(decisionId, com.aatlas.decisions.DecisionStatus.APPLIED);
        });
    }

    /** {@code approvals.ApprovalRejected}: the award never became a purchase - reopen the round. */
    @Transactional
    void onApprovalRejected(UUID decisionId) {
        UUID tenantId = TenantContext.requireTenantId();
        rfqs.findByTenantIdAndDecisionId(tenantId, decisionId).ifPresent(entity -> {
            entity.setStatus(RfqEntity.Status.quoted);
            rfqs.save(entity);
        });
    }

    private void recordPurchase(RfqEntity entity, RfqQuoteEntity quote, RfqInviteEntity invite, UUID decisionId) {
        ledger.recordPurchase(new RecordPurchaseRequest(entity.getItemNumber(), entity.getItemName(),
                quote.getName(), entity.getQty(), invite.getExpectedLanded(), invite.getExpectedLanded(),
                quote.getQuotedLanded(), null, true, entity.getDestinationId(), decisionId, quote.getSupplierId(),
                invite.getCountry(), null));
    }

    // -- export --------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    String exportCsv(UUID id) {
        UUID tenantId = TenantContext.requireTenantId();
        rfqs.findByTenantIdAndId(tenantId, id).orElseThrow(() -> ApiException.notFound("Rfq", id));
        List<RfqQuoteEntity> rows = quotes.findByTenantIdAndRfqId(tenantId, id);

        StringWriter out = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT.builder()
                .setHeader("supplier_id", "name", "declined", "quoted_unit", "quoted_landed", "currency",
                        "lead_days", "valid_until", "payment_terms", "note", "vs_expected_pct", "received_at")
                .get())) {
            for (RfqQuoteEntity q : rows) {
                csv.printRecord(q.getSupplierId(), q.getName(), q.isDeclined(), q.getQuotedUnit(),
                        q.getQuotedLanded(), q.getCurrency(), q.getLeadDays(), q.getValidUntil(),
                        q.getPaymentTerms(), q.getNote(), q.getVsExpectedPct(), q.getReceivedAt());
            }
            csv.flush();
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }

    private static BigDecimal bd(double n) {
        return BigDecimal.valueOf(n);
    }
}
