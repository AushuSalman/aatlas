package com.aatlas.rfq;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One RFQ round, with its invites and quotes. The frontend's {@code Rfq}. */
@Schema(name = "Rfq")
public record Rfq(
        UUID id,
        String ref,
        Instant at,
        String itemNumber,
        String itemName,
        String regionKey,
        String regionLabel,
        String destinationId,
        String destinationLabel,
        int qty,
        int requiredDays,
        String urgency,
        String priority,
        String incoterm,
        String paymentTerms,
        String notes,
        String message,
        List<RfqInvite> invites,
        List<RfqQuote> quotes,
        RfqStatus status,
        Instant closesAt,
        String awardedSupplierId,
        Instant awardedAt,
        /** Set once award writes or raises a decision - {@code decisions.Decision#id}. */
        UUID decisionId,
        UUID createdBy) {
}
