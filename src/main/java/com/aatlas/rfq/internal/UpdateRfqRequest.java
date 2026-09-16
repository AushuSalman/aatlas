package com.aatlas.rfq.internal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * {@code PATCH /rfqs/{id}}'s body - only while the round is still {@code draft}. Any field
 * left null keeps its current value; the invite panel itself is not recomputed (a documented
 * simplification - re-drafting a round after inviting different suppliers means creating a
 * new one).
 */
@Schema(name = "UpdateRfqRequest")
record UpdateRfqRequest(Integer qty, Integer requiredDays, String priority, String notes) {
}
