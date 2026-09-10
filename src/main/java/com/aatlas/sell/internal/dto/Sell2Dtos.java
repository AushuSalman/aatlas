package com.aatlas.sell.internal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

/** Wire records mirroring {@code src/lib/intel/sell2.ts}. */
public final class Sell2Dtos {

    private Sell2Dtos() {
    }

    public record ToneLineDto(String label, String value, String tone) {
    }

    public record CustomerProfileDto(String profile, int slaDays, String label, String fulfilment, List<String> traits) {
    }

    public record SpeedTierDto(
            String key, String label, String requirement, BigDecimal price, BigDecimal premiumPct,
            BigDecimal premiumAbs, BigDecimal marginPct, List<String> customers) {
    }

    public record SpeedPricingDto(List<SpeedTierDto> tiers, BigDecimal premiumPct, boolean cappedByPolicy, String explanation) {
    }

    public record HoldDecisionDto(
            String recommendation,
            int days,
            BigDecimal priceNow,
            BigDecimal priceLater,
            BigDecimal appreciation,
            BigDecimal holdingCost,
            BigDecimal depreciationRisk,
            String demandUncertainty,
            BigDecimal uncertaintyCost,
            BigDecimal net,
            BigDecimal netTotal,
            String reason,
            List<ToneLineDto> lines) {
    }

    public record LiquidationDto(
            boolean active, int units, BigDecimal valueNow, BigDecimal valueIn60, BigDecimal erosion,
            int discountPct, String reason) {
    }

    public record GuardrailCheckDto(
            boolean adjusted, BigDecimal original, @JsonProperty("final") BigDecimal finalPrice, BigDecimal limit,
            String rule, String reason) {
    }

    public record ScorePartDto(String label, int score) {
    }

    public record SellDecisionScoreDto(int total, List<ScorePartDto> parts, String risk) {
    }

    public record SellWhatIfDto(String title, List<ToneLineDto> rows, String note) {
    }

    public record SellScenarioOptionDto(String key, String label) {
    }

    public record AtpOrderDto(
            String customerId, String name, String profile, String profileLabel, int slaDays, int qty,
            BigDecimal marginPct) {
    }

    public record AtpLotDto(String supplierId, String supplierName, BigDecimal reliabilityPct, int units, int remaining) {
    }

    public record AtpFromDto(String lot, int units, BigDecimal reliabilityPct) {
    }

    public record AtpLineDto(
            AtpOrderDto order, int allocated, List<AtpFromDto> from, @JsonProperty("short") int shortUnits, String note) {
    }

    public record AtpAllocationDto(
            int available, int reserve, List<AtpLotDto> lots, List<AtpLineDto> lines, int demanded, int unmet,
            String explanation) {
    }
}
