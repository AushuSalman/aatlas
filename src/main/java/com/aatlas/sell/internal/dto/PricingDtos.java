package com.aatlas.sell.internal.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Wire records mirroring {@code src/lib/types.ts} and the parts of {@code
 * src/lib/mock/pricing.ts} every response embeds, field for field (including the fields
 * the frontend itself kept snake_case - {@code range_min}, {@code delta_vs_current} and
 * friends - because the TypeScript interface uses them and the frontend renders these
 * responses without remapping).
 *
 * <p>Every field a real number in TypeScript is {@link BigDecimal} here rather than a
 * primitive or boxed {@code double}: {@code ArchitectureRulesTest.noFloatingPointMoney}
 * forbids a dependency on {@code java.lang.Double} anywhere under {@code com.aatlas.sell},
 * and {@code BigDecimal} is also the one representation that is naturally nullable, which
 * several of these fields (competitor prices when there are none, a guardrail limit when
 * nothing bound) genuinely are. The arithmetic itself happens in {@code
 * com.aatlas.sell.internal.engine} using primitive {@code double}, matching the
 * JavaScript engine bit for bit; these records only wrap the already-rounded result.
 */
public final class PricingDtos {

    private PricingDtos() {
    }

    public record CompetitorDto(String name, String domain, BigDecimal price, BigDecimal deltaVsCurrent) {
    }

    public record DemandInfoDto(
            String level,
            String label,
            BigDecimal index,
            BigDecimal movePercent,
            String confidence,
            BigDecimal confWeight,
            BigDecimal recentVelocity,
            BigDecimal expectedVelocity,
            int maxAdjustmentPct,
            String trendDirection,
            int historyDays) {
    }

    public record BenchmarksDto(BigDecimal lowest, BigDecimal average, BigDecimal highest) {
    }

    public record PriceBandDto(String label, BigDecimal rangeMin, BigDecimal rangeMax, int transactionCount) {
    }

    public record CalcStepDto(String label, String value, String note, String kind) {
    }

    public record FactorWeightDto(String label, BigDecimal percent, String direction, String note) {

        public FactorWeightDto(String label, BigDecimal percent, String direction) {
            this(label, percent, direction, null);
        }
    }

    public record PriceHeaderDto(
            BigDecimal recommendedPrice,
            BigDecimal currentPrice,
            BigDecimal deltaAbsolute,
            BigDecimal deltaPercent,
            BigDecimal optimalPrice,
            BigDecimal optimalDeltaAbsolute,
            BigDecimal optimalDeltaPercent,
            BigDecimal aggressivePrice,
            BigDecimal aggressiveDeltaAbsolute,
            BigDecimal aggressiveDeltaPercent,
            String recommendedTier) {
    }

    /** {@code PriceTierView}: one of the two tiers on {@code buildSellRecommendation}. */
    public record PriceTierDto(
            String label, BigDecimal price, BigDecimal marginPct, BigDecimal deltaAbs, BigDecimal deltaPct,
            BigDecimal upliftPerUnit, String blurb) {
    }

    /** {@code SellRecommendation}: the "why this price" derivation, {@code buildSellRecommendation}. */
    public record SellDerivationDto(
            String itemNumber,
            String description,
            String storeId,
            String storeName,
            boolean priceable,
            BigDecimal cost,
            BigDecimal currentPrice,
            BigDecimal currentMarginPct,
            PriceTierDto optimal,
            PriceTierDto aggressive,
            String recommendedTier,
            DemandInfoDto demand,
            List<CompetitorDto> competitors,
            BenchmarksDto benchmarks,
            List<PriceBandDto> bands,
            BigDecimal observedMin,
            BigDecimal observedMax,
            int totalTransactions,
            List<CalcStepDto> steps,
            List<FactorWeightDto> weights,
            BigDecimal marginFloor,
            BigDecimal peerBandFloor,
            BigDecimal ceilingPrice) {
    }
}
