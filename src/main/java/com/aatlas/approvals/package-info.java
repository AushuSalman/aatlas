/**
 * Approval requests raised when a decision exceeds the seat's limit.
 *
 * <p>Application module. Types in this package root are the public API other
 * modules may depend on; everything under it is internal.
 *
 * <p>Generic on purpose: a raising module ({@code buy}, {@code rfq}, a future bulk-award
 * flow) never learns how approvals are routed or decided - it calls {@link
 * com.aatlas.approvals.ApprovalRequester#raise} with a decision already recorded as {@link
 * com.aatlas.decisions.DecisionStatus#PENDING} and an amount, then listens for {@link
 * com.aatlas.approvals.ApprovalGranted}/{@link com.aatlas.approvals.ApprovalRejected} to
 * finish what committing that decision actually means for it (writing a deal, in {@code
 * buy}'s and {@code rfq}'s case). This module itself never writes a deal - see {@code
 * decisions.DecisionRecorder#resolve}, which is all {@link
 * com.aatlas.approvals.internal.ApprovalService} calls.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "approvals",
        allowedDependencies = {"common", "decisions", "policy"})
package com.aatlas.approvals;
