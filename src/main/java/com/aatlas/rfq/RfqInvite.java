package com.aatlas.rfq;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One supplier invited into a round, with the panel figures the invitation was drawn
 * against - the frontend's {@code RfqInvite} in {@code intel/rfq.ts}, plus {@code sentAt} and
 * an invite-level {@code status} for the real send/reply lifecycle the browser-only
 * prototype never needed.
 */
@Schema(name = "RfqInvite")
public record RfqInvite(
        String supplierId,
        String name,
        String country,
        String route,
        double expectedLanded,
        int leadDays,
        double onTimePct,
        String riskLevel,
        Instant sentAt,
        /** {@code invited}, {@code quoted} or {@code declined} - mirrors the linked {@link RfqQuote}, if any. */
        String status) {
}
