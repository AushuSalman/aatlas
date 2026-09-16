package com.aatlas.rfq.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code POST /rfqs/{id}/quotes}'s body. Every invite this round is still waiting on gets a
 * reply: one named here is entered as given; every other outstanding invite gets a
 * deterministic simulated reply (the frontend's {@code simulateQuote}) - there is no real
 * supplier inbox behind this yet, so a demo round can still be walked start to finish.
 * An empty or omitted {@code replies} list simulates every outstanding invite.
 */
@Schema(name = "EnterQuotesRequest")
record EnterQuotesRequest(List<Reply> replies) {

    @Schema(name = "RfqQuoteReply")
    record Reply(
            String supplierId,
            boolean declined,
            BigDecimal quotedUnit,
            BigDecimal quotedLanded,
            Integer leadDays,
            Integer validDays,
            String paymentTerms,
            String note) {
    }
}
