package com.aatlas.sell.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** Wire records mirroring {@code src/lib/intel/sell.ts}'s {@code SellIntel} and friends. */
public final class SellDtos {

    private SellDtos() {
    }

    public record ChainStepDto(String key, String label, String value, String effect, String note, BigDecimal price) {
    }

    public record TimelinePointDto(String label, BigDecimal value, BigDecimal low, BigDecimal high, String kind) {
    }

    public record ForecastSummaryDto(BigDecimal d30, BigDecimal d60, BigDecimal d90, BigDecimal driftPct90, String driver) {
    }

    public record NowSummaryDto(BigDecimal price, BigDecimal marginPct, BigDecimal demandPct) {
    }

    public record WaitSummaryDto(int days, BigDecimal price, BigDecimal extraPerUnit, BigDecimal extraTotal, String risk) {
    }

    public record NowVsWaitDto(
            String recommendation, String reason, NowSummaryDto now, @JsonProperty("wait") WaitSummaryDto waitOption) {
    }

    public record ScenarioResultDto(
            BigDecimal pct,
            BigDecimal price,
            int units,
            BigDecimal revenue,
            BigDecimal revenueDelta,
            BigDecimal revenueDeltaPct,
            BigDecimal marginPct,
            BigDecimal marginDeltaPts,
            BigDecimal demandDeltaPct,
            BigDecimal profit,
            BigDecimal profitDelta,
            BigDecimal profitDeltaPct) {
    }

    /** The frontend's {@code SellIntel}, field for field. */
    public record SellIntelDto(
            String itemNumber,
            String storeId,
            boolean priceable,
            String name,
            String description,
            String storeLabel,
            String regionLabel,
            String category,

            BigDecimal cost,
            BigDecimal currentPrice,
            BigDecimal marketPrice,
            BigDecimal recommended,
            BigDecimal stretchPrice,
            BigDecimal marginFloor,
            BigDecimal ceiling,
            BigDecimal currentMarginPct,
            BigDecimal expectedMarginPct,
            BigDecimal upliftPerUnit,
            BigDecimal upliftPct,

            int confidence,
            String confidenceLabel,

            int competitorCount,
            BigDecimal competitorLow,
            BigDecimal competitorHigh,
            BigDecimal demandPct,
            String demandLabel,
            BigDecimal regionalAdj,

            List<ChainStepDto> chain,
            @JsonProperty("final") ChainStepDto finalStep,

            List<TimelinePointDto> timeline,
            ForecastSummaryDto forecast,
            NowVsWaitDto nowVsWait,

            BigDecimal elasticity,
            int monthlyUnits,
            int annualUnits,
            int inventoryUnits,
            BigDecimal inventoryValue,
            BigDecimal weeksOfCover,

            BigDecimal monthlyOpportunity) {
    }
}
