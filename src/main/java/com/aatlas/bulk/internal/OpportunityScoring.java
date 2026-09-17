package com.aatlas.bulk.internal;

import com.aatlas.bulk.SellLine;
import com.aatlas.history.PricingMath;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * The 0-100 opportunity score {@code bulkSellPlan} shows per line: {@link
 * PricingMath#score}, fed only by what {@link SellLine} itself already carries off {@code
 * sell.SellLines} - no separate lookup, no seeded conversion draw. A part whose input is
 * locked on the line (margin without a cost, weeks of cover without a stock count) scores
 * exactly 0, the same "skipped, never zeroed" rule every history-backed formula follows;
 * {@code recommended} stands in for the market anchor {@link SellLine} does not carry
 * separately - it is already the anchor/own-reference blend the sell engine computed, so
 * the price-gap part still measures current price against what the market says it should
 * be.
 */
@Component
public class OpportunityScoring {

    public record Score(int score, String tier) {
    }

    public Score score(SellLine line) {
        if (!line.priceable()) {
            return new Score(0, "risk");
        }
        boolean marginLocked = line.locked().contains("margin");
        boolean inventoryLocked = line.locked().contains("inventory");

        PricingMath.Score s = PricingMath.score(new PricingMath.ScoreInputs(
                line.demandLevel(),
                BigDecimal.valueOf(line.recommended()),
                BigDecimal.valueOf(line.currentPrice()),
                null,
                marginLocked ? null : BigDecimal.valueOf(line.currentMarginPct()),
                inventoryLocked ? null : BigDecimal.valueOf(line.weeksOfCover()),
                null,
                0));
        return new Score(s.score(), s.tier());
    }
}
