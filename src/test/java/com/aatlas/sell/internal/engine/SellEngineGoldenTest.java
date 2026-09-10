package com.aatlas.sell.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.aatlas.sell.internal.dto.SellDtos.ChainStepDto;
import com.aatlas.sell.internal.dto.SellDtos.SellIntelDto;
import com.aatlas.sell.internal.dto.SellDtos.TimelinePointDto;
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
 * Pins {@link SellEngine#getSellIntel} against {@code golden/sell-intel.json}: 84 {@code
 * {fn:"getSellIntel", input:{itemNumber,storeId}, output}} rows, the same 84 pairs as
 * {@code pricing-model.json}.
 */
class SellEngineGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOL = 1.0e-6;

    private static JsonNode golden() throws Exception {
        try (InputStream in = SellEngineGoldenTest.class.getResourceAsStream("/golden/sell-intel.json")) {
            assertThat(in).as("golden/sell-intel.json on the test classpath").isNotNull();
            return JSON.readTree(in);
        }
    }

    static SellEngine engine() {
        FixtureCatalog catalog = new FixtureCatalog();
        PricingEngine pricing = new PricingEngine(catalog);
        var clock = com.aatlas.common.time.AatlasClock.fixed(Instant.parse("2026-09-01T09:20:00Z"), ZoneOffset.UTC);
        ElasticityEngine elasticity = new ElasticityEngine(catalog, pricing, new FixtureSuppliers(), clock);
        return new SellEngine(catalog, pricing, elasticity);
    }

    private static void assertMoney(String ctx, java.math.BigDecimal actual, JsonNode expected) {
        assertThat(actual.doubleValue()).as(ctx).isCloseTo(expected.asDouble(), offset(TOL));
    }

    private static void assertMoneyOrNull(String ctx, java.math.BigDecimal actual, JsonNode expected) {
        if (expected == null || expected.isNull()) {
            assertThat(actual).as(ctx + " should be null").isNull();
        } else {
            assertThat(actual).as(ctx + " should not be null").isNotNull();
            assertMoney(ctx, actual, expected);
        }
    }

    private static void assertChainStep(String ctx, ChainStepDto actual, JsonNode expected) {
        assertThat(actual.key()).as(ctx + ".key").isEqualTo(expected.get("key").asText());
        assertThat(actual.label()).as(ctx + ".label").isEqualTo(expected.get("label").asText());
        assertThat(actual.value()).as(ctx + ".value").isEqualTo(expected.get("value").asText());
        JsonNode effect = expected.get("effect");
        if (effect == null || effect.isNull()) {
            assertThat(actual.effect()).as(ctx + ".effect should be null").isNull();
        } else {
            assertThat(actual.effect()).as(ctx + ".effect").isEqualTo(effect.asText());
        }
        assertThat(actual.note()).as(ctx + ".note").isEqualTo(expected.get("note").asText());
        assertMoney(ctx + ".price", actual.price(), expected.get("price"));
    }

    @Test
    void reproducesEveryRow() throws Exception {
        SellEngine engine = engine();
        JsonNode rows = golden();
        assertThat(rows.size()).isEqualTo(84);

        for (JsonNode row : rows) {
            assertThat(row.get("fn").asText()).isEqualTo("getSellIntel");
            String item = row.get("input").get("itemNumber").asText();
            String storeId = row.get("input").get("storeId").asText();
            JsonNode e = row.get("output");
            String ctx = item + "@" + storeId;

            SellIntelDto a = engine.getSellIntel(item, storeId);

            assertThat(a.priceable()).as(ctx + " priceable").isEqualTo(e.get("priceable").asBoolean());
            assertThat(a.name()).as(ctx + " name").isEqualTo(e.get("name").asText());
            assertThat(a.description()).as(ctx + " description").isEqualTo(e.get("description").asText());
            assertThat(a.storeLabel()).as(ctx + " storeLabel").isEqualTo(e.get("storeLabel").asText());
            assertThat(a.regionLabel()).as(ctx + " regionLabel").isEqualTo(e.get("regionLabel").asText());
            assertThat(a.category()).as(ctx + " category").isEqualTo(e.get("category").asText());

            if (!e.get("priceable").asBoolean()) {
                continue; // the non-priceable branch is all zeros/empties; not part of the 84 golden pairs anyway.
            }

            assertMoney(ctx + " cost", a.cost(), e.get("cost"));
            assertMoney(ctx + " currentPrice", a.currentPrice(), e.get("currentPrice"));
            assertMoney(ctx + " marketPrice", a.marketPrice(), e.get("marketPrice"));
            assertMoney(ctx + " recommended", a.recommended(), e.get("recommended"));
            assertMoney(ctx + " stretchPrice", a.stretchPrice(), e.get("stretchPrice"));
            assertMoney(ctx + " marginFloor", a.marginFloor(), e.get("marginFloor"));
            assertMoney(ctx + " ceiling", a.ceiling(), e.get("ceiling"));
            assertMoney(ctx + " currentMarginPct", a.currentMarginPct(), e.get("currentMarginPct"));
            assertMoney(ctx + " expectedMarginPct", a.expectedMarginPct(), e.get("expectedMarginPct"));
            assertMoney(ctx + " upliftPerUnit", a.upliftPerUnit(), e.get("upliftPerUnit"));
            assertMoney(ctx + " upliftPct", a.upliftPct(), e.get("upliftPct"));

            assertThat(a.confidence()).as(ctx + " confidence").isEqualTo(e.get("confidence").asInt());
            assertThat(a.confidenceLabel()).as(ctx + " confidenceLabel").isEqualTo(e.get("confidenceLabel").asText());
            assertThat(a.competitorCount()).as(ctx + " competitorCount").isEqualTo(e.get("competitorCount").asInt());
            assertMoneyOrNull(ctx + " competitorLow", a.competitorLow(), e.get("competitorLow"));
            assertMoneyOrNull(ctx + " competitorHigh", a.competitorHigh(), e.get("competitorHigh"));
            assertMoney(ctx + " demandPct", a.demandPct(), e.get("demandPct"));
            assertThat(a.demandLabel()).as(ctx + " demandLabel").isEqualTo(e.get("demandLabel").asText());
            assertMoney(ctx + " regionalAdj", a.regionalAdj(), e.get("regionalAdj"));

            JsonNode chain = e.get("chain");
            assertThat(a.chain()).as(ctx + " chain size").hasSize(chain.size());
            for (int i = 0; i < chain.size(); i++) {
                assertChainStep(ctx + " chain[" + i + "]", a.chain().get(i), chain.get(i));
            }
            assertChainStep(ctx + " final", a.finalStep(), e.get("final"));

            JsonNode timeline = e.get("timeline");
            List<TimelinePointDto> tl = a.timeline();
            assertThat(tl).as(ctx + " timeline size").hasSize(timeline.size());
            for (int i = 0; i < timeline.size(); i++) {
                JsonNode tp = timeline.get(i);
                TimelinePointDto actual = tl.get(i);
                assertThat(actual.label()).as(ctx + " timeline[" + i + "].label").isEqualTo(tp.get("label").asText());
                assertMoney(ctx + " timeline[" + i + "].value", actual.value(), tp.get("value"));
                assertMoneyOrNull(ctx + " timeline[" + i + "].low", actual.low(), tp.get("low"));
                assertMoneyOrNull(ctx + " timeline[" + i + "].high", actual.high(), tp.get("high"));
                assertThat(actual.kind()).as(ctx + " timeline[" + i + "].kind").isEqualTo(tp.get("kind").asText());
            }

            JsonNode forecast = e.get("forecast");
            assertMoney(ctx + " forecast.d30", a.forecast().d30(), forecast.get("d30"));
            assertMoney(ctx + " forecast.d60", a.forecast().d60(), forecast.get("d60"));
            assertMoney(ctx + " forecast.d90", a.forecast().d90(), forecast.get("d90"));
            assertMoney(ctx + " forecast.driftPct90", a.forecast().driftPct90(), forecast.get("driftPct90"));
            assertThat(a.forecast().driver()).as(ctx + " forecast.driver").isEqualTo(forecast.get("driver").asText());

            JsonNode nvw = e.get("nowVsWait");
            assertThat(a.nowVsWait().recommendation()).as(ctx + " nowVsWait.recommendation")
                    .isEqualTo(nvw.get("recommendation").asText());
            assertThat(a.nowVsWait().reason()).as(ctx + " nowVsWait.reason").isEqualTo(nvw.get("reason").asText());
            JsonNode now = nvw.get("now");
            assertMoney(ctx + " nowVsWait.now.price", a.nowVsWait().now().price(), now.get("price"));
            assertMoney(ctx + " nowVsWait.now.marginPct", a.nowVsWait().now().marginPct(), now.get("marginPct"));
            assertMoney(ctx + " nowVsWait.now.demandPct", a.nowVsWait().now().demandPct(), now.get("demandPct"));
            JsonNode wait = nvw.get("wait");
            assertThat(a.nowVsWait().waitOption().days()).as(ctx + " nowVsWait.wait.days").isEqualTo(wait.get("days").asInt());
            assertMoney(ctx + " nowVsWait.wait.price", a.nowVsWait().waitOption().price(), wait.get("price"));
            assertMoney(ctx + " nowVsWait.wait.extraPerUnit", a.nowVsWait().waitOption().extraPerUnit(), wait.get("extraPerUnit"));
            assertMoney(ctx + " nowVsWait.wait.extraTotal", a.nowVsWait().waitOption().extraTotal(), wait.get("extraTotal"));
            assertThat(a.nowVsWait().waitOption().risk()).as(ctx + " nowVsWait.wait.risk").isEqualTo(wait.get("risk").asText());

            assertMoney(ctx + " elasticity", a.elasticity(), e.get("elasticity"));
            assertThat(a.monthlyUnits()).as(ctx + " monthlyUnits").isEqualTo(e.get("monthlyUnits").asInt());
            assertThat(a.annualUnits()).as(ctx + " annualUnits").isEqualTo(e.get("annualUnits").asInt());
            assertThat(a.inventoryUnits()).as(ctx + " inventoryUnits").isEqualTo(e.get("inventoryUnits").asInt());
            assertMoney(ctx + " inventoryValue", a.inventoryValue(), e.get("inventoryValue"));
            assertMoney(ctx + " weeksOfCover", a.weeksOfCover(), e.get("weeksOfCover"));
            assertMoney(ctx + " monthlyOpportunity", a.monthlyOpportunity(), e.get("monthlyOpportunity"));
        }
    }
}
