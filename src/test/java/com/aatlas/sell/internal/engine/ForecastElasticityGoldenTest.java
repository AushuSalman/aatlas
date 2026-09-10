package com.aatlas.sell.internal.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ElasticityModelDto;
import com.aatlas.sell.internal.dto.ForecastElasticityDtos.ForecastModelDto;
import com.aatlas.sell.internal.dto.PricingDtos.CalcStepDto;
import com.aatlas.sell.internal.dto.PricingDtos.FactorWeightDto;
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
 * Pins {@link ForecastEngine#getForecastModel} and {@link ElasticityEngine#getElasticityModel}
 * against {@code golden/forecast-elasticity.json}: 26 rows, mixed function.
 */
class ForecastElasticityGoldenTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double TOL = 1.0e-6;

    private static JsonNode golden() throws Exception {
        try (InputStream in = ForecastElasticityGoldenTest.class.getResourceAsStream("/golden/forecast-elasticity.json")) {
            assertThat(in).as("golden/forecast-elasticity.json on the test classpath").isNotNull();
            return JSON.readTree(in);
        }
    }

    private static void assertCalcSteps(String ctx, List<CalcStepDto> actual, JsonNode expected) {
        assertThat(actual).as(ctx + " steps size").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode s = expected.get(i);
            CalcStepDto a = actual.get(i);
            assertThat(a.label()).as(ctx + " steps[" + i + "].label").isEqualTo(s.get("label").asText());
            JsonNode value = s.get("value");
            if (value == null || value.isNull()) {
                assertThat(a.value()).as(ctx + " steps[" + i + "].value should be null").isNull();
            } else {
                assertThat(a.value()).as(ctx + " steps[" + i + "].value").isEqualTo(value.asText());
            }
            JsonNode note = s.get("note");
            if (note == null || note.isNull()) {
                assertThat(a.note()).as(ctx + " steps[" + i + "].note should be null").isNull();
            } else {
                assertThat(a.note()).as(ctx + " steps[" + i + "].note").isEqualTo(note.asText());
            }
            assertThat(a.kind()).as(ctx + " steps[" + i + "].kind").isEqualTo(s.get("kind").asText());
        }
    }

    private static void assertWeights(String ctx, List<FactorWeightDto> actual, JsonNode expected) {
        assertThat(actual).as(ctx + " weights size").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode w = expected.get(i);
            FactorWeightDto a = actual.get(i);
            assertThat(a.label()).as(ctx + " weights[" + i + "].label").isEqualTo(w.get("label").asText());
            assertThat(a.percent().doubleValue()).as(ctx + " weights[" + i + "].percent")
                    .isCloseTo(w.get("percent").asDouble(), offset(TOL));
            JsonNode direction = w.get("direction");
            if (direction == null || direction.isNull()) {
                assertThat(a.direction()).as(ctx + " weights[" + i + "].direction should be null").isNull();
            } else {
                assertThat(a.direction()).as(ctx + " weights[" + i + "].direction").isEqualTo(direction.asText());
            }
            JsonNode note = w.get("note");
            if (note == null || note.isNull()) {
                assertThat(a.note()).as(ctx + " weights[" + i + "].note should be null").isNull();
            } else {
                assertThat(a.note()).as(ctx + " weights[" + i + "].note").isEqualTo(note.asText());
            }
        }
    }

    @Test
    void reproducesEveryRow() throws Exception {
        FixtureCatalog catalog = new FixtureCatalog();
        PricingEngine pricing = new PricingEngine(catalog);
        ForecastEngine forecastEngine = new ForecastEngine(catalog, pricing);
        var clock = com.aatlas.common.time.AatlasClock.fixed(Instant.parse("2026-09-01T09:20:00Z"), ZoneOffset.UTC);
        ElasticityEngine elasticityEngine = new ElasticityEngine(catalog, pricing, new FixtureSuppliers(), clock);

        JsonNode rows = golden();
        int forecastRows = 0;
        int elasticityRows = 0;

        for (JsonNode row : rows) {
            String fn = row.get("fn").asText();
            JsonNode input = row.get("input");
            JsonNode e = row.get("output");

            if (fn.equals("getForecastModel")) {
                forecastRows++;
                String item = input.get("itemNumber").asText();
                String storeId = input.get("storeId").asText();
                String horizon = input.get("horizon").asText();
                String ctx = "forecast " + item + "@" + storeId + "/" + horizon;

                ForecastModelDto a = forecastEngine.getForecastModel(item, storeId, horizon);
                assertThat(a.priceable()).as(ctx + " priceable").isEqualTo(e.get("priceable").asBoolean());
                if (!e.get("priceable").asBoolean()) {
                    continue;
                }
                assertThat(a.trend()).as(ctx + " trend").isEqualTo(e.get("trend").asText());
                assertThat(a.trendPct().doubleValue()).as(ctx + " trendPct").isCloseTo(e.get("trendPct").asDouble(), offset(TOL));
                assertThat(a.intermittent()).as(ctx + " intermittent").isEqualTo(e.get("intermittent").asBoolean());
                assertThat(a.coldStart()).as(ctx + " coldStart").isEqualTo(e.get("coldStart").asBoolean());
                assertThat(a.structuralBreak()).as(ctx + " structuralBreak").isEqualTo(e.get("structuralBreak").asBoolean());

                JsonNode points = e.get("points");
                assertThat(a.points()).as(ctx + " points size").hasSize(points.size());
                for (int i = 0; i < points.size(); i++) {
                    JsonNode p = points.get(i);
                    assertThat(a.points().get(i).period()).as(ctx + " points[" + i + "].period").isEqualTo(p.get("period").asText());
                    assertThat(a.points().get(i).p10()).as(ctx + " points[" + i + "].p10").isEqualTo(p.get("p10").asInt());
                    assertThat(a.points().get(i).p50()).as(ctx + " points[" + i + "].p50").isEqualTo(p.get("p50").asInt());
                    assertThat(a.points().get(i).p90()).as(ctx + " points[" + i + "].p90").isEqualTo(p.get("p90").asInt());
                }

                JsonNode acc = e.get("accuracy");
                assertThat(a.accuracy().naiveMape().doubleValue()).as(ctx + " accuracy.naiveMape")
                        .isCloseTo(acc.get("naiveMape").asDouble(), offset(TOL));
                assertThat(a.accuracy().modelMape().doubleValue()).as(ctx + " accuracy.modelMape")
                        .isCloseTo(acc.get("modelMape").asDouble(), offset(TOL));
                assertThat(a.accuracy().humanMape().doubleValue()).as(ctx + " accuracy.humanMape")
                        .isCloseTo(acc.get("humanMape").asDouble(), offset(TOL));
                assertThat(a.accuracy().fvaPp().doubleValue()).as(ctx + " accuracy.fvaPp")
                        .isCloseTo(acc.get("fvaPp").asDouble(), offset(TOL));

                assertWeights(ctx, a.weights(), e.get("weights"));
                assertCalcSteps(ctx, a.steps(), e.get("steps"));
            } else if (fn.equals("getElasticityModel")) {
                elasticityRows++;
                String item = input.get("itemNumber").asText();
                String side = input.get("side").asText();
                JsonNode cpNode = input.get("counterpartyId");
                String counterpartyId = (cpNode == null || cpNode.isNull()) ? null : cpNode.asText();
                String ctx = "elasticity " + item + "/" + side + "/" + counterpartyId;

                ElasticityModelDto a = elasticityEngine.getElasticityModel(item, side, counterpartyId);
                assertThat(a.priceable()).as(ctx + " priceable").isEqualTo(e.get("priceable").asBoolean());
                if (!e.get("priceable").asBoolean()) {
                    continue;
                }
                assertThat(a.side()).as(ctx + " side").isEqualTo(e.get("side").asText());
                assertThat(a.counterpartyLabel()).as(ctx + " counterpartyLabel").isEqualTo(e.get("counterpartyLabel").asText());
                assertThat(a.coefficient().doubleValue()).as(ctx + " coefficient").isCloseTo(e.get("coefficient").asDouble(), offset(TOL));
                assertThat(a.confidenceScore()).as(ctx + " confidenceScore").isEqualTo(e.get("confidenceScore").asInt());
                JsonNode band = e.get("confidenceBand");
                assertThat(a.confidenceBand().get(0).doubleValue()).as(ctx + " confidenceBand[0]")
                        .isCloseTo(band.get(0).asDouble(), offset(TOL));
                assertThat(a.confidenceBand().get(1).doubleValue()).as(ctx + " confidenceBand[1]")
                        .isCloseTo(band.get(1).asDouble(), offset(TOL));
                assertThat(a.granularity()).as(ctx + " granularity").isEqualTo(e.get("granularity").asText());
                assertThat(a.usedFallback()).as(ctx + " usedFallback").isEqualTo(e.get("usedFallback").asBoolean());

                JsonNode curve = e.get("curve");
                assertThat(a.curve()).as(ctx + " curve size").hasSize(curve.size());
                for (int i = 0; i < curve.size(); i++) {
                    assertThat(a.curve().get(i).x().doubleValue()).as(ctx + " curve[" + i + "].x")
                            .isCloseTo(curve.get(i).get("x").asDouble(), offset(TOL));
                    assertThat(a.curve().get(i).y().doubleValue()).as(ctx + " curve[" + i + "].y")
                            .isCloseTo(curve.get(i).get("y").asDouble(), offset(TOL));
                }

                JsonNode cross = e.get("cross");
                assertThat(a.cross()).as(ctx + " cross size").hasSize(cross.size());
                for (int i = 0; i < cross.size(); i++) {
                    JsonNode c = cross.get(i);
                    assertThat(a.cross().get(i).label()).as(ctx + " cross[" + i + "].label").isEqualTo(c.get("label").asText());
                    assertThat(a.cross().get(i).detail()).as(ctx + " cross[" + i + "].detail").isEqualTo(c.get("detail").asText());
                    assertThat(a.cross().get(i).effectPct().doubleValue()).as(ctx + " cross[" + i + "].effectPct")
                            .isCloseTo(c.get("effectPct").asDouble(), offset(TOL));
                    assertThat(a.cross().get(i).kind()).as(ctx + " cross[" + i + "].kind").isEqualTo(c.get("kind").asText());
                }

                JsonNode experiments = e.get("experiments");
                assertThat(a.experiments()).as(ctx + " experiments size").hasSize(experiments.size());
                for (int i = 0; i < experiments.size(); i++) {
                    JsonNode x = experiments.get(i);
                    assertThat(a.experiments().get(i).id()).as(ctx + " experiments[" + i + "].id").isEqualTo(x.get("id").asText());
                    assertThat(a.experiments().get(i).label()).as(ctx + " experiments[" + i + "].label").isEqualTo(x.get("label").asText());
                    assertThat(a.experiments().get(i).date()).as(ctx + " experiments[" + i + "].date").isEqualTo(x.get("date").asText());
                    assertThat(a.experiments().get(i).predicted().doubleValue()).as(ctx + " experiments[" + i + "].predicted")
                            .isCloseTo(x.get("predicted").asDouble(), offset(TOL));
                    assertThat(a.experiments().get(i).realised().doubleValue()).as(ctx + " experiments[" + i + "].realised")
                            .isCloseTo(x.get("realised").asDouble(), offset(TOL));
                }

                assertWeights(ctx, a.weights(), e.get("weights"));
                assertCalcSteps(ctx, a.steps(), e.get("steps"));
            } else {
                throw new AssertionError("Unexpected fn in forecast-elasticity.json: " + fn);
            }
        }

        assertThat(forecastRows).as("getForecastModel rows exercised").isEqualTo(13);
        assertThat(elasticityRows).as("getElasticityModel rows exercised").isEqualTo(13);
    }
}
