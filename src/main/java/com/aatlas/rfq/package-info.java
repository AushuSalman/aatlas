/**
 * Request-for-quotation rounds, invites, supplier quotes and award.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal.
 *
 * <p>Ports {@code intel/rfq.ts} onto real tables: {@code createRfq}, {@code simulateQuote},
 * {@code awardRfq} and {@code recommendQuote} become {@code POST /rfqs}, {@code POST
 * /rfqs/{id}/quotes}, {@code POST /rfqs/{id}/award} and {@code GET
 * /rfqs/{id}/recommendation}. Built against {@code buy}'s public {@link
 * com.aatlas.buy.BuyIntelReader}/{@link com.aatlas.buy.ProcurementPlanReader} - a round is
 * an invitation drawn from the same ranked supplier panel the Buy screen already shows,
 * never a second copy of that scoring.
 *
 * <p>Award follows the same approval gate {@code buy.BuySelectRequest} does ({@link
 * com.aatlas.policy.Persona#canApprove}), but through the real {@code approvals} module
 * rather than a local stand-in: a seat within limit gets an immediate {@code
 * decisions.DecisionRecorder#recordPurchase}; one over it gets {@code
 * decisions.DecisionRecorder#recordPending} plus {@code
 * approvals.ApprovalRequester#raise}, and the purchase is written later, when {@code
 * approvals.ApprovalGranted} arrives for that round's decision.
 */
@org.springframework.modulith.ApplicationModule(displayName = "rfq")
package com.aatlas.rfq;
