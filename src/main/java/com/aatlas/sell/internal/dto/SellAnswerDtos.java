package com.aatlas.sell.internal.dto;

import com.aatlas.sell.DecisionRecorder.Recorded;
import com.aatlas.sell.OpportunityScoreView;
import com.aatlas.sell.internal.dto.DealDtos.DealQuoteDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.CustomerProfileDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.GuardrailCheckDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.HoldDecisionDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.LiquidationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SellDecisionScoreDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SellWhatIfDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SpeedPricingDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SpeedTierDto;
import com.aatlas.sell.internal.dto.SellDtos.ScenarioResultDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import java.math.BigDecimal;

/**
 * Response envelopes that bundle several engine calls into one HTTP round trip - what {@code
 * GET /sell/recommendation} and the two write endpoints answer - built for exactly what
 * {@code src/app/app/sell/page.tsx} reads on load (see WAVE2-BRIEF).
 */
public final class SellAnswerDtos {

    private SellAnswerDtos() {
    }

    /**
     * The whole Sell answer for one (item, store): the guardrail-adjusted recommendation and
     * everything {@code answerFor} in {@code page.tsx} computes alongside it, plus the
     * opportunity chip. The four nested answers are {@code null} exactly when {@code
     * intel.priceable} is {@code false} (no sales history), mirroring {@code a === null} on
     * the frontend.
     */
    public record SellRecommendationDto(
            SellIntelDto intel,
            GuardrailCheckDto guardrailCheck,
            SellDecisionScoreDto decisionScore,
            LiquidationDto liquidation,
            SpeedPricingDto speedPricing,
            HoldDecisionDto holdVsSell,
            OpportunityScoreView opportunityScore) {
    }

    public record ScenarioResponseDto(ScenarioResultDto priceMove, SellWhatIfDto scenario) {
    }

    public record QuoteResponseDto(
            DealQuoteDto quote,
            CustomerProfileDto customerProfile,
            SpeedTierDto speedTier,
            boolean cappedByDiscountPolicy,
            BigDecimal requestedDiscountPct,
            BigDecimal dealPrice,
            BigDecimal profit) {
    }

    public record ApplyResponseDto(Recorded decision) {
    }

    public record StarterDto(String itemNumber, String storeId, String name, String storeLabel, BigDecimal upliftPct) {
    }
}
