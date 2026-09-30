package com.aatlas.buy;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * How the tenant's buying model bore on one target: how many of its steps were on, how far
 * a phased-in target has come, and anything the chain had to resolve on the way. The
 * frontend's {@code BuyModelSummary}.
 *
 * @param on         buy-side toggles switched on
 * @param total      buy-side toggles in the model
 * @param maturity   0-1, how far from today's cost toward the full target the shown target
 *                   sits; null when nothing is being phased in
 * @param fullTarget the target once fully phased in; null when the target shown is already it
 * @param flags      {@code target_at_floor} (held at the lowest real price on file),
 *                   {@code move_capped} (the move cap limited it this run),
 *                   {@code competitor_implausible} (a market price was left out as implausible),
 *                   {@code market_gap} (several open-market prices agree with each other but sit far
 *                   from the suppliers' quotes, so the target follows the market at once)
 */
@Schema(name = "BuyModelSummary")
public record BuyModelSummary(int on, int total, BigDecimal maturity, BigDecimal fullTarget, List<String> flags) {

    public static final String FLAG_TARGET_AT_FLOOR = "target_at_floor";
    public static final String FLAG_MOVE_CAPPED = "move_capped";
    public static final String FLAG_COMPETITOR_IMPLAUSIBLE = "competitor_implausible";
    public static final String FLAG_MARKET_GAP = "market_gap";
}
