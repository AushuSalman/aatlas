package com.aatlas.rfq;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * The answer to {@code POST /rfqs/{id}/award} - the same shape {@code buy.BuySelectResult}
 * uses for the identical decision ({@link com.aatlas.policy.Persona#canApprove}): {@code ok}
 * when the seat could commit the award alone, {@code pending} when it needs a sign-off.
 */
@Schema(name = "RfqAwardResult")
public record RfqAwardResult(boolean ok, Boolean pending, BigDecimal limit, String approver) {

    public static RfqAwardResult approved() {
        return new RfqAwardResult(true, null, null, null);
    }

    public static RfqAwardResult pending(BigDecimal limit, String approver) {
        return new RfqAwardResult(false, true, limit, approver);
    }
}
