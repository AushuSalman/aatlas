package com.aatlas.decisions;

import com.aatlas.common.web.CursorPage;
import java.util.UUID;

/**
 * What a user actually did, recorded. The public write/read surface {@code sell}, {@code buy}
 * and {@code bulk} wrote {@code DecisionRecorder}-shaped stand-ins against during wave 2 - this
 * is the real thing; retargeting one of those stand-ins onto this interface should be a rename
 * of the stand-in's call sites, not a rewrite.
 *
 * <h2>Shape</h2>
 * <ul>
 *   <li>{@link #record} - one {@link Decision} row. Ports the frontend's {@code recordDecision}.
 *       Carries an optional {@link QuoteBreakdown} for a sell decision with deal context.</li>
 *   <li>{@link #recordSale} - one sell-side {@link DealRecord}. Ports the frontend's
 *       {@code recordSale} ({@code platform/recorded.ts}) - a separate file from
 *       {@code recordDecision} in the frontend because the browser-only prototype wrote two
 *       independent localStorage keys from one page action; here it is one call. Pass the
 *       {@link Decision}'s id (from {@link #record}) as {@code decisionId} to link the two.</li>
 *   <li>{@link #recordPurchase} - one buy-side {@link DealRecord}, and - when
 *       {@code supplierId}/{@code country} are given - also badges a real line onto the
 *       {@code analytics} procurement ledger via {@code analytics.ProcurementLedger}, carrying
 *       {@code decisionId} so the ledger can show it alongside the seeded backdrop. Ports the
 *       frontend's {@code recordPurchase} ({@code intel/decisions.ts}).</li>
 *   <li>{@link #list} - recent decisions, newest first, keyset paged.</li>
 *   <li>{@link #get} - one decision, 404 if it is not this tenant's.</li>
 * </ul>
 *
 * <p>The tenant and user are read from {@code TenantContext} - never accepted as a parameter,
 * matching every other write path in the API.
 */
public interface DecisionRecorder {

    Decision record(RecordDecisionRequest request);

    DealRecord recordSale(RecordSaleRequest request);

    DealRecord recordPurchase(RecordPurchaseRequest request);

    Decision get(UUID id);

    CursorPage<Decision> list(int limit, String cursor);
}
