package com.aatlas.suppliers.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.common.seed.Seeded;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the supplier engines against {@code golden/suppliers.json}, produced by running the
 * TypeScript prototype's own {@code panelSuppliers}, {@code panelSummary},
 * {@code commercialTerms} and {@code lookupSupplier}.
 *
 * <p>Every figure the seeded panel and a fresh lookup show is reconstructed here from
 * {@code seed/suppliers.json}'s {@code record} fields (id, country, lead time, OTIF, price
 * index - the inputs a real caller would have) through the same engines the service uses,
 * and compared field for field against what the TypeScript actually produced. A drift of
 * one rounding step anywhere in the chain fails a test here before it reaches a screen.
 */
class SupplierEngineGoldenTest {

    private static final ObjectMapper TREE = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private static final ObjectMapper PLAIN = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    /**
     * Structural equality that treats {@code 4} and {@code 4.0} as the same value. The
     * golden file is literal JSON from {@code JSON.stringify}, which prints a whole number
     * without a decimal point ({@code "rating":4}); a Java {@code double} field always
     * serialises with one ({@code "rating":4.0}). Jackson's own {@code JsonNode.equals}
     * treats an {@code IntNode} and a {@code DoubleNode} of the same value as unequal, which
     * would fail every whole-number field for a reason that has nothing to do with the
     * engine being ported.
     */
    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.asDouble() == b.asDouble();
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<String> names = a.fieldNames();
            while (names.hasNext()) {
                String f = names.next();
                if (!b.has(f) || !jsonEquals(a.get(f), b.get(f))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static JsonNode golden() throws Exception {
        try (InputStream in = SupplierEngineGoldenTest.class.getResourceAsStream("/golden/suppliers.json")) {
            assertThat(in).as("golden/suppliers.json on the test classpath").isNotNull();
            return PLAIN.readTree(in);
        }
    }

    private static List<SeedSupplierEntry> seed() throws Exception {
        try (InputStream in = SupplierEngineGoldenTest.class.getResourceAsStream("/seed/suppliers.json")) {
            assertThat(in).as("seed/suppliers.json on the test classpath").isNotNull();
            return PLAIN.readValue(in,
                    new com.fasterxml.jackson.core.type.TypeReference<List<SeedSupplierEntry>>() {
                    });
        }
    }

    /** Rebuilds one seeded supplier's profile the way {@code profileFromRecord} does. */
    private static SupplierProfileView recompute(SeedSupplierEntry entry) {
        SupplierProfileView seeded = entry.profile();
        String id = seeded.id();
        String key = "sup:" + id;
        String country = seeded.country();
        int leadTimeDays = seeded.leadTimeDays();
        double otifPct = seeded.otifPct();
        double priceIndex = seeded.priceIndex();
        String category = seeded.category();

        double defectPct = SupplierScoring.seededDefect(id);
        RatingBreakdown breakdown = new RatingBreakdown(
                SupplierScoring.qualityStars(defectPct),
                SupplierScoring.deliveryStars(id, country, leadTimeDays, otifPct),
                SupplierScoring.communicationStars(key),
                SupplierScoring.pricingStars(priceIndex));
        double rating = SupplierScoring.overall(breakdown);

        return new SupplierProfileView(
                id, seeded.name(), country, seeded.city(), seeded.website(), category,
                Seeded.randInt(key, "years-trading", 8, 55),
                SupplierScoring.certsFor(key, category),
                rating,
                Seeded.randInt(key, "rating-n", 40, 380),
                "Verified buyers",
                breakdown,
                SupplierScoring.reviewsFor(key, rating),
                seeded.spendShare12m(),
                leadTimeDays, otifPct, priceIndex, defectPct,
                SupplierScoring.holdsStock(id),
                false, null, null, null, null, null);
    }

    @Test
    void reproducesEverySeededSupplierProfile() throws Exception {
        for (SeedSupplierEntry entry : seed()) {
            SupplierProfileView computed = recompute(entry);
            JsonNode expected = TREE.valueToTree(entry.profile());
            JsonNode actual = TREE.valueToTree(computed);
            assertThat(jsonEquals(actual, expected))
                    .as("profile for %s%nexpected=%s%nactual=%s", entry.profile().id(), expected, actual)
                    .isTrue();
        }
    }

    @Test
    void cascadeCopperMillsIsExcellentAndGulfStatesIsWeak() throws Exception {
        for (SeedSupplierEntry entry : seed()) {
            if ("Cascade Copper Mills".equals(entry.profile().name())) {
                assertThat(entry.profile().rating()).isEqualTo(4.5);
                assertThat(SupplierScoring.ratingLabel(entry.profile().rating())).isEqualTo("Excellent");
            }
            if ("Gulf States Polymer".equals(entry.profile().name())) {
                assertThat(entry.profile().rating()).isEqualTo(3.0);
                assertThat(SupplierScoring.ratingLabel(entry.profile().rating())).isEqualTo("Weak");
            }
        }
    }

    @Test
    void reproducesThePanelSummary() throws Exception {
        List<SeedSupplierEntry> entries = seed();
        List<SupplierScoring.PanelRow> rows = entries.stream()
                .map(e -> new SupplierScoring.PanelRow(false, e.profile().rating(), e.profile().spendShare12m(),
                        e.profile().otifPct()))
                .toList();
        PanelSummary summary = SupplierScoring.panelSummary(rows);
        JsonNode expected = golden().get("summary");
        assertThat(summary.size()).isEqualTo(expected.get("size").asInt());
        assertThat(summary.added()).isEqualTo(expected.get("added").asInt());
        assertThat(summary.avgRating()).isEqualTo(expected.get("avgRating").asDouble());
        assertThat(summary.spendRated4Pct()).isEqualTo(expected.get("spendRated4Pct").asInt());
        assertThat(summary.weak()).isEqualTo(expected.get("weak").asInt());
        assertThat(summary.avgOtifPct()).isEqualTo(expected.get("avgOtifPct").asDouble());
    }

    @Test
    void reproducesThePanelSummaryWithACustomSupplierAdded() throws Exception {
        List<SeedSupplierEntry> entries = seed();
        List<SupplierScoring.PanelRow> rows = new ArrayList<>(entries.stream()
                .map(e -> new SupplierScoring.PanelRow(false, e.profile().rating(), e.profile().spendShare12m(),
                        e.profile().otifPct()))
                .toList());

        LookupScoring.Outcome halden = LookupScoring.lookup("Halden Metals Ltd", null);
        rows.add(new SupplierScoring.PanelRow(true, halden.profile().rating(), halden.profile().spendShare12m(),
                halden.profile().otifPct()));

        PanelSummary summary = SupplierScoring.panelSummary(rows);
        JsonNode expected = golden().get("summaryWithCustom");
        assertThat(summary.size()).isEqualTo(expected.get("size").asInt());
        assertThat(summary.added()).isEqualTo(expected.get("added").asInt());
        assertThat(summary.avgRating()).isEqualTo(expected.get("avgRating").asDouble());
        assertThat(summary.spendRated4Pct()).isEqualTo(expected.get("spendRated4Pct").asInt());
        assertThat(summary.weak()).isEqualTo(expected.get("weak").asInt());
        assertThat(summary.avgOtifPct()).isEqualTo(expected.get("avgOtifPct").asDouble());
    }

    @Test
    void reproducesEveryTermsValue() throws Exception {
        // Group golden's flat termsValues array back into one block per supplier id: three
        // unitCost checks followed by one labels/watchOuts check, in that order.
        ArrayNode all = (ArrayNode) golden().get("termsValues");
        Iterator<JsonNode> it = all.elements();
        while (it.hasNext()) {
            JsonNode unitCost1 = it.next();
            String id = unitCost1.get("id").asText();
            JsonNode unitCost2 = it.next();
            JsonNode unitCost3 = it.next();
            JsonNode labelsRow = it.next();

            String country = seed().stream()
                    .filter(e -> e.profile().id().equals(id))
                    .findFirst().orElseThrow().profile().country();
            CommercialTerms t = TermsScoring.commercialTerms(id, country);

            for (JsonNode row : List.of(unitCost1, unitCost2, unitCost3)) {
                double unitCost = row.get("unitCost").asDouble();
                assertThat(TermsScoring.creditValuePerUnit(unitCost, t.creditDays()))
                        .as(id + " creditValue @ " + unitCost)
                        .isEqualTo(row.get("creditValue").asDouble());
                assertThat(TermsScoring.earlyPayNetPerUnit(unitCost, t))
                        .as(id + " earlyPayNet @ " + unitCost)
                        .isEqualTo(row.get("earlyPayNet").asDouble());
                assertThat(TermsScoring.penaltyRecoveryCapPerUnit(unitCost, t))
                        .as(id + " penaltyCap @ " + unitCost)
                        .isEqualTo(row.get("penaltyCap").asDouble());
            }

            JsonNode labels = labelsRow.get("labels");
            assertThat(TermsScoring.latePenaltyLabel(t)).isEqualTo(labels.get("latePenalty").asText());
            assertThat(TermsScoring.creditLabel(t)).isEqualTo(labels.get("credit").asText());
            assertThat(TermsScoring.earlyPayLabel(t)).isEqualTo(labels.get("earlyPay").asText());

            List<String> watchOuts100 = TermsScoring.termsWatchOuts(t, 100);
            List<String> expected100 = toStringList(labelsRow.get("watchOuts100"));
            assertThat(watchOuts100).as(id + " watchOuts @ qty=100").isEqualTo(expected100);

            List<String> watchOuts30000 = TermsScoring.termsWatchOuts(t, 30000);
            List<String> expected30000 = toStringList(labelsRow.get("watchOuts30000"));
            assertThat(watchOuts30000).as(id + " watchOuts @ qty=30000").isEqualTo(expected30000);
        }
    }

    private static List<String> toStringList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void reproducesEveryLookup() throws Exception {
        for (JsonNode row : golden().get("lookups")) {
            String query = row.get("query").asText();
            String country = row.get("country").isNull() ? null : row.get("country").asText();
            JsonNode expected = row.get("result");

            LookupScoring.Outcome outcome = LookupScoring.lookup(query, country);

            assertThat(outcome.query()).as(query).isEqualTo(expected.get("query").asText());
            assertThat(outcome.recommendation()).as(query).isEqualTo(expected.get("recommendation").asText());

            JsonNode expectedProfile = expected.get("profile");
            JsonNode actualProfile = TREE.valueToTree(outcome.profile());
            assertThat(jsonEquals(actualProfile, expectedProfile))
                    .as("%s profile%nexpected=%s%nactual=%s", query, expectedProfile, actualProfile)
                    .isTrue();

            JsonNode expectedSources = expected.get("sources");
            JsonNode actualSources = TREE.valueToTree(outcome.sources());
            assertThat(jsonEquals(actualSources, expectedSources))
                    .as("%s sources%nexpected=%s%nactual=%s", query, expectedSources, actualSources)
                    .isTrue();

            List<String> expectedWatchOuts = toStringList(expected.get("watchOuts"));
            assertThat(outcome.watchOuts()).as(query + " watchOuts").isEqualTo(expectedWatchOuts);
        }
    }
}
