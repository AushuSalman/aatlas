package com.aatlas.sell.internal.dto;

import com.aatlas.sell.internal.dto.PricingDtos.CalcStepDto;
import com.aatlas.sell.internal.dto.PricingDtos.FactorWeightDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Wire records for {@code src/lib/platform/forecast.ts} ({@code getForecastModel}) and
 * {@code src/lib/platform/elasticity.ts} ({@code getElasticityModel}).
 */
public final class ForecastElasticityDtos {

    private ForecastElasticityDtos() {
    }

    public record ForecastPointDto(String period, int p10, int p50, int p90) {
    }

    public record AccuracyDto(BigDecimal naiveMape, BigDecimal modelMape, BigDecimal humanMape, BigDecimal fvaPp) {
    }

    public record ForecastModelDto(
            String itemNumber,
            String description,
            String storeId,
            String horizon,
            boolean priceable,
            List<ForecastPointDto> points,
            String trend,
            BigDecimal trendPct,
            boolean intermittent,
            boolean coldStart,
            boolean structuralBreak,
            AccuracyDto accuracy,
            List<FactorWeightDto> weights,
            List<CalcStepDto> steps) {
    }

    public record ResponsePointDto(BigDecimal x, BigDecimal y) {
    }

    public record CrossItemDto(String label, String detail, BigDecimal effectPct, String kind) {
    }

    public record ExperimentRowDto(String id, String label, String date, BigDecimal predicted, BigDecimal realised) {
    }

    public record ElasticityModelDto(
            String itemNumber,
            String description,
            String side,
            String counterpartyLabel,
            boolean priceable,
            BigDecimal coefficient,
            int confidenceScore,
            List<BigDecimal> confidenceBand,
            String granularity,
            boolean usedFallback,
            List<ResponsePointDto> curve,
            List<CrossItemDto> cross,
            List<ExperimentRowDto> experiments,
            List<FactorWeightDto> weights,
            List<CalcStepDto> steps) {
    }
}
