package com.aatlas.sell.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.aatlas.sell.internal.catalog.CatalogRefs.ProductRef;
import com.aatlas.sell.internal.dto.Sell2Dtos.GuardrailCheckDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.HoldDecisionDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.LiquidationDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SellDecisionScoreDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SellWhatIfDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.SpeedPricingDto;
import com.aatlas.sell.internal.dto.Sell2Dtos.ToneLineDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import com.aatlas.sell.internal.policy.GuardrailValues;
import com.aatlas.sell.internal.support.FixtureCatalog;
import com.aatlas.sell.internal.support.FixtureSuppliers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link Sell2Engine} against {@code golden/sell-decisions.json}: 840 rows, ten calls
 * per priceable pair - {@code applyGuardrails}, {@code sellDecisionScore}, {@code
 * liquidationSignal}, {@code speedPricing}, {@code holdVsSell}, {@code sellWhatIf} (four
 * scenarios) and {@code allocateInventory}.
 *
 * <p>{@code allocateInventory} rows are counted but not asserted: that function's stock lots
 * come from Track Buy's landed-cost engine, which is not in this worktree - see {@link
 * AtpEngine}'s doc comment and the report for what stands in for it and why golden parity
 * there is out of scope for this track.
 */
class Sell2EngineGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOL = 1.0e-6;

    private static JsonNode golden() throws Exception {
        try (InputStream in = Sell2EngineGoldenTest.class.getResourceAsStream("/golden/sell-decisions.json")) {
            assertThat(in).as("golden/sell-decisions.json on the test classpath").isNotNull();
            return JSON.readTree(in);
        }
    }

    private static void assertToneLines(String ctx, List<ToneLineDto> actual, JsonNode expected) {
        assertThat(actual).as(ctx + " size").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode l = expected.get(i);
            ToneLineDto a = actual.get(i);
            assertThat(a.label()).as(ctx + "[" + i + "].label").isEqualTo(l.get("label").asText());
            assertThat(a.value()).as(ctx + "[" + i + "].value").isEqualTo(l.get("value").asText());
            JsonNode tone = l.get("tone");
            if (tone == null || tone.isNull()) {
                assertThat(a.tone()).as(ctx + "[" + i + "].tone should be null").isNull();
            } else {
                assertThat(a.tone()).as(ctx + "[" + i + "].tone").isEqualTo(tone.asText());
            }
        }
    }

    @Test
    void reproducesEveryRow() throws Exception {
        FixtureCatalog catalog = new FixtureCatalog();
        PricingEngine pricing = new PricingEngine(catalog);
        var clock = com.aatlas.common.time.AatlasClock.fixed(Instant.parse("2026-09-01T09:20:00Z"), ZoneOffset.UTC);
        ElasticityEngine elasticityEngine = new ElasticityEngine(catalog, pricing, new FixtureSuppliers(), clock);
        SellEngine sellEngine = new SellEngine(catalog, pricing, elasticityEngine);
        Sell2Engine sell2 = new Sell2Engine(catalog);

        JsonNode rows = golden();
        assertThat(rows.size()).isEqualTo(840);

        java.util.Map<String, Integer> counts = new java.util.HashMap<>();

        for (JsonNode row : rows) {
            String fn = row.get("fn").asText();
            counts.merge(fn, 1, Integer::sum);
            JsonNode input = row.get("input");
            JsonNode e = row.get("output");
            JsonNode intelInput = input.get("intel");
            String itemNumber = intelInput.get("itemNumber").asText();
            String storeId = intelInput.get("storeId").asText();
            String ctx = fn + " " + itemNumber + "@" + storeId;

            if (fn.equals("allocateInventory")) {
                continue;
            }

            SellIntelDto intel = sellEngine.getSellIntel(itemNumber, storeId);
            String commodityKey = catalog.findProduct(itemNumber).map(ProductRef::commodity).orElse("none");

            switch (fn) {
                case "applyGuardrails" -> {
                    GuardrailCheckDto a = sell2.applyGuardrails(intel, GuardrailValues.DEFAULTS);
                    assertThat(a.adjusted()).as(ctx + " adjusted").isEqualTo(e.get("adjusted").asBoolean());
                    assertThat(a.original().doubleValue()).as(ctx + " original").isCloseTo(e.get("original").asDouble(), offset(TOL));
                    assertThat(a.finalPrice().doubleValue()).as(ctx + " final").isCloseTo(e.get("final").asDouble(), offset(TOL));
                    JsonNode limit = e.get("limit");
                    if (limit == null || limit.isNull()) {
                        assertThat(a.limit()).as(ctx + " limit should be null").isNull();
                    } else {
                        assertThat(a.limit().doubleValue()).as(ctx + " limit").isCloseTo(limit.asDouble(), offset(TOL));
                    }
                    assertThat(a.rule()).as(ctx + " rule").isEqualTo(e.get("rule").asText());
                    assertThat(a.reason()).as(ctx + " reason").isEqualTo(e.get("reason").asText());
                }
                case "sellDecisionScore" -> {
                    double conversionPct = input.get("conversionPct").asDouble();
                    SellDecisionScoreDto a = sell2.sellDecisionScore(intel, conversionPct);
                    assertThat(a.total()).as(ctx + " total").isEqualTo(e.get("total").asInt());
                    JsonNode parts = e.get("parts");
                    assertThat(a.parts()).as(ctx + " parts size").hasSize(parts.size());
                    for (int i = 0; i < parts.size(); i++) {
                        assertThat(a.parts().get(i).label()).as(ctx + " parts[" + i + "].label").isEqualTo(parts.get(i).get("label").asText());
                        assertThat(a.parts().get(i).score()).as(ctx + " parts[" + i + "].score").isEqualTo(parts.get(i).get("score").asInt());
                    }
                    assertThat(a.risk()).as(ctx + " risk").isEqualTo(e.get("risk").asText());
                }
                case "liquidationSignal" -> {
                    LiquidationDto a = sell2.liquidationSignal(intel);
                    assertThat(a.active()).as(ctx + " active").isEqualTo(e.get("active").asBoolean());
                    assertThat(a.units()).as(ctx + " units").isEqualTo(e.get("units").asInt());
                    assertThat(a.valueNow().doubleValue()).as(ctx + " valueNow").isCloseTo(e.get("valueNow").asDouble(), offset(TOL));
                    assertThat(a.valueIn60().doubleValue()).as(ctx + " valueIn60").isCloseTo(e.get("valueIn60").asDouble(), offset(TOL));
                    assertThat(a.erosion().doubleValue()).as(ctx + " erosion").isCloseTo(e.get("erosion").asDouble(), offset(TOL));
                    assertThat(a.discountPct()).as(ctx + " discountPct").isEqualTo(e.get("discountPct").asInt());
                    assertThat(a.reason()).as(ctx + " reason").isEqualTo(e.get("reason").asText());
                }
                case "speedPricing" -> {
                    SpeedPricingDto a = sell2.speedPricing(intel, GuardrailValues.DEFAULTS);
                    JsonNode tiers = e.get("tiers");
                    assertThat(a.tiers()).as(ctx + " tiers size").hasSize(tiers.size());
                    for (int i = 0; i < tiers.size(); i++) {
                        JsonNode t = tiers.get(i);
                        var at = a.tiers().get(i);
                        assertThat(at.key()).as(ctx + " tiers[" + i + "].key").isEqualTo(t.get("key").asText());
                        assertThat(at.label()).as(ctx + " tiers[" + i + "].label").isEqualTo(t.get("label").asText());
                        assertThat(at.requirement()).as(ctx + " tiers[" + i + "].requirement").isEqualTo(t.get("requirement").asText());
                        assertThat(at.price().doubleValue()).as(ctx + " tiers[" + i + "].price").isCloseTo(t.get("price").asDouble(), offset(TOL));
                        assertThat(at.premiumPct().doubleValue()).as(ctx + " tiers[" + i + "].premiumPct").isCloseTo(t.get("premiumPct").asDouble(), offset(TOL));
                        assertThat(at.premiumAbs().doubleValue()).as(ctx + " tiers[" + i + "].premiumAbs").isCloseTo(t.get("premiumAbs").asDouble(), offset(TOL));
                        assertThat(at.marginPct().doubleValue()).as(ctx + " tiers[" + i + "].marginPct").isCloseTo(t.get("marginPct").asDouble(), offset(TOL));
                        JsonNode customers = t.get("customers");
                        assertThat(at.customers()).as(ctx + " tiers[" + i + "].customers").hasSize(customers.size());
                        for (int j = 0; j < customers.size(); j++) {
                            assertThat(at.customers().get(j)).as(ctx + " tiers[" + i + "].customers[" + j + "]")
                                    .isEqualTo(customers.get(j).asText());
                        }
                    }
                    assertThat(a.premiumPct().doubleValue()).as(ctx + " premiumPct").isCloseTo(e.get("premiumPct").asDouble(), offset(TOL));
                    assertThat(a.cappedByPolicy()).as(ctx + " cappedByPolicy").isEqualTo(e.get("cappedByPolicy").asBoolean());
                    assertThat(a.explanation()).as(ctx + " explanation").isEqualTo(e.get("explanation").asText());
                }
                case "holdVsSell" -> {
                    int days = input.get("days").asInt();
                    HoldDecisionDto a = sell2.holdVsSell(intel, days, commodityKey);
                    assertThat(a.recommendation()).as(ctx + " recommendation").isEqualTo(e.get("recommendation").asText());
                    assertThat(a.days()).as(ctx + " days").isEqualTo(e.get("days").asInt());
                    assertThat(a.priceNow().doubleValue()).as(ctx + " priceNow").isCloseTo(e.get("priceNow").asDouble(), offset(TOL));
                    assertThat(a.priceLater().doubleValue()).as(ctx + " priceLater").isCloseTo(e.get("priceLater").asDouble(), offset(TOL));
                    assertThat(a.appreciation().doubleValue()).as(ctx + " appreciation").isCloseTo(e.get("appreciation").asDouble(), offset(TOL));
                    assertThat(a.holdingCost().doubleValue()).as(ctx + " holdingCost").isCloseTo(e.get("holdingCost").asDouble(), offset(TOL));
                    assertThat(a.depreciationRisk().doubleValue()).as(ctx + " depreciationRisk").isCloseTo(e.get("depreciationRisk").asDouble(), offset(TOL));
                    assertThat(a.demandUncertainty()).as(ctx + " demandUncertainty").isEqualTo(e.get("demandUncertainty").asText());
                    assertThat(a.uncertaintyCost().doubleValue()).as(ctx + " uncertaintyCost").isCloseTo(e.get("uncertaintyCost").asDouble(), offset(TOL));
                    assertThat(a.net().doubleValue()).as(ctx + " net").isCloseTo(e.get("net").asDouble(), offset(TOL));
                    assertThat(a.netTotal().doubleValue()).as(ctx + " netTotal").isCloseTo(e.get("netTotal").asDouble(), offset(TOL));
                    assertThat(a.reason()).as(ctx + " reason").isEqualTo(e.get("reason").asText());
                    assertToneLines(ctx + " lines", a.lines(), e.get("lines"));
                }
                case "sellWhatIf" -> {
                    String scenario = input.get("scenario").asText();
                    SellWhatIfDto a = sell2.sellWhatIf(intel, scenario, commodityKey);
                    assertThat(a.title()).as(ctx + " title").isEqualTo(e.get("title").asText());
                    assertToneLines(ctx + " rows", a.rows(), e.get("rows"));
                    assertThat(a.note()).as(ctx + " note").isEqualTo(e.get("note").asText());
                }
                default -> throw new AssertionError("Unexpected fn in sell-decisions.json: " + fn);
            }
        }

        assertThat(counts.get("applyGuardrails")).isEqualTo(84);
        assertThat(counts.get("sellDecisionScore")).isEqualTo(84);
        assertThat(counts.get("liquidationSignal")).isEqualTo(84);
        assertThat(counts.get("speedPricing")).isEqualTo(84);
        assertThat(counts.get("holdVsSell")).isEqualTo(84);
        assertThat(counts.get("sellWhatIf")).isEqualTo(84 * 4);
        assertThat(counts.get("allocateInventory")).isEqualTo(84);
    }
}
