package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * The answer to {@code POST /buy/select}. Mirrors what {@code canApprove()}
 * (frontend {@code platform/session.ts}) already produces locally, now decided by
 * {@link com.aatlas.policy.Persona#canApprove(BigDecimal)}: {@code ok} when the seat can
 * commit this order alone, {@code pending} (with {@code limit}/{@code approver}) when it
 * needs a sign-off above the seat's approval limit.
 */
@Schema(name = "BuySelectResult")
public record BuySelectResult(boolean ok, Boolean pending, BigDecimal limit, String approver) {

    public static BuySelectResult approved() {
        return new BuySelectResult(true, null, null, null);
    }

    public static BuySelectResult pending(BigDecimal limit, String approver) {
        return new BuySelectResult(false, true, limit, approver);
    }
}
