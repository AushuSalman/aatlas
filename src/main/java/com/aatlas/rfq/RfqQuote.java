package com.aatlas.rfq;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** What one invited supplier came back with. The frontend's {@code RfqQuote}. */
@Schema(name = "RfqQuote")
public record RfqQuote(
        UUID id,
        String supplierId,
        String name,
        boolean declined,
        /** Landed cost per unit, as quoted. Null when declined. */
        Double quotedUnit,
        String currency,
        Integer leadDays,
        LocalDate validUntil,
        String paymentTerms,
        String note,
        /** Against what the platform expected this supplier to land at. Null when declined. */
        Double vsExpectedPct,
        Instant receivedAt,
        /** Null when the reply was simulated rather than typed in by a buyer. */
        UUID enteredBy) {
}
