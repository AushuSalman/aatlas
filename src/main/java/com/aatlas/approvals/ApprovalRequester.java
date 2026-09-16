package com.aatlas.approvals;

/**
 * The public write seam: any module that gates a commit behind {@code
 * Persona#canApprove} calls this when a seat cannot approve alone, instead of inventing its
 * own pending-request table. See the package Javadoc for the full raise -&gt; listen -&gt;
 * commit flow.
 */
public interface ApprovalRequester {

    ApprovalRequest raise(RaiseApprovalRequest request);
}
