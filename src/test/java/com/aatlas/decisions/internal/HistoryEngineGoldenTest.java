package com.aatlas.decisions.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.aatlas.analytics.BuyImpactSummary;
import com.aatlas.analytics.internal.ledger.BuyAnalytics;
import com.aatlas.analytics.internal.ledger.BuyAnalyticsEngine;
import com.aatlas.analytics.internal.ledger.DateRange;
import com.aatlas.analytics.internal.ledger.DateRanges;
import com.aatlas.analytics.internal.ledger.Filters;
import com.aatlas.analytics.internal.ledger.LedgerBuilder;
import com.aatlas.analytics.internal.ledger.PoRow;
import com.aatlas.decisions.DealRecord;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link HistoryEngine} against {@code golden/history.json}: {@code getHistory} and
 * {@code buildImpact}, run over the 181 seeded deals with no recorded deals and no decisions
 * - exactly the browser state the golden file was generated with (see {@code golden/README.md}).
 *
 * <p>The buy side of {@code buildImpact} crosses into {@code analytics}: this test builds the
 * procurement ledger and runs {@code BuyAnalyticsEngine} directly (no Spring, no database) the
 * same way {@code ProcurementAnalyticsService.trailingTwelveMonthImpact()} does at runtime, so
 * both modules' ports are exercised together exactly as the golden fixture was generated.
 */
class HistoryEngineGoldenTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final ObjectMapper TREE = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SeedDeal(String id, LocalDate date, String side, String itemNumber, String description,
            String counterparty, int qty, BigDecimal cost, BigDecimal baselinePrice, BigDecimal suggestedPrice,
            BigDecimal actualPrice, boolean followed, BigDecimal gain, BigDecimal lost) {
    }

    /**
     * The 181 seeded deals, in {@code seed/deals.json}'s own order - already the frontend's
     * sorted {@code DEALS} export (see the file's own generation notes), so no re-sort is
     * needed to reproduce {@code buildImpact}'s {@code [...recorded, ...DEALS].sort(...)} with
     * an empty {@code recorded}.
     */
    private static List<DealRecord> seededDeals() throws Exception {
        try (InputStream in = HistoryEngineGoldenTest.class.getResourceAsStream("/seed/deals.json")) {
            assertThat(in).as("seed/deals.json on the test classpath").isNotNull();
            List<SeedDeal> rows = PLAIN.readValue(in, new TypeReference<List<SeedDeal>>() {
            });
            return rows.stream()
                    .map(r -> new DealRecord(r.id(), r.date(), r.side(), r.itemNumber(), r.description(),
                            r.counterparty(), r.qty(), r.cost(), r.baselinePrice(), r.suggestedPrice(),
                            r.actualPrice(), r.followed(), r.gain(), r.lost(), null, null, null, null, null, null))
                    .toList();
        }
    }

    private static BuyImpactSummary buyImpact() {
        List<PoRow> ledger = LedgerBuilder.build(TODAY);
        DateRange range = DateRanges.resolveRange("12m", TODAY);
        BuyAnalytics a = BuyAnalyticsEngine.compute(range, Filters.none(), ledger);
        List<BuyImpactSummary.MonthPoint> byMonth = a.timeline().stream()
                .map(t -> new BuyImpactSummary.MonthPoint(t.bucket().label(), t.saved(), t.leaked()))
                .toList();
        return new BuyImpactSummary(a.lines(), a.captureRate().value(), a.saved().value(), a.leaked().value(), byMonth);
    }

    private static List<JsonNode> golden() throws Exception {
        try (InputStream in = HistoryEngineGoldenTest.class.getResourceAsStream("/golden/history.json")) {
            assertThat(in).as("golden/history.json on the test classpath").isNotNull();
            JsonNode root = PLAIN.readTree(in);
            return java.util.stream.StreamSupport.stream(root.spliterator(), false).toList();
        }
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || a.isNull() || b == null || b.isNull()) {
            return (a == null || a.isNull()) && (b == null || b.isNull());
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

    /** First path where two trees differ, for a debuggable failure message instead of a full dump. */
    private static String firstDiff(JsonNode a, JsonNode b, String path) {
        if (a == null || a.isNull() || b == null || b.isNull()) {
            boolean aNull = a == null || a.isNull();
            boolean bNull = b == null || b.isNull();
            return aNull == bNull ? null : path + ": actual=" + a + " expected=" + b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.asDouble() == b.asDouble() ? null : path + ": actual=" + a + " expected=" + b;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                java.util.Set<String> onlyA = new java.util.TreeSet<>();
                a.fieldNames().forEachRemaining(onlyA::add);
                java.util.Set<String> bAll = new java.util.TreeSet<>();
                b.fieldNames().forEachRemaining(bAll::add);
                onlyA.removeAll(bAll);
                java.util.Set<String> onlyB = new java.util.TreeSet<>();
                b.fieldNames().forEachRemaining(onlyB::add);
                java.util.Set<String> aAll = new java.util.TreeSet<>();
                a.fieldNames().forEachRemaining(aAll::add);
                onlyB.removeAll(aAll);
                return path + ": size actual=" + a.size() + " expected=" + b.size()
                        + " onlyInActual=" + onlyA + " onlyInExpected=" + onlyB;
            }
            Iterator<String> names = a.fieldNames();
            while (names.hasNext()) {
                String f = names.next();
                String d = firstDiff(a.get(f), b.get(f), path + "." + f);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return path + ": array size actual=" + a.size() + " expected=" + b.size();
            }
            for (int i = 0; i < a.size(); i++) {
                String d = firstDiff(a.get(i), b.get(i), path + "[" + i + "]");
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
        return a.equals(b) ? null : path + ": actual=" + a + " expected=" + b;
    }

    @Test
    void getHistoryMatches() throws Exception {
        List<DealRecord> all = seededDeals();
        ImpactData impact = HistoryEngine.buildImpact(all, List.of(), buyImpact());
        HistorySummary history = HistoryEngine.getHistory(impact, List.of());

        JsonNode expected = golden().get(0).get("output");
        JsonNode actual = TREE.valueToTree(history);
        assertThat(firstDiff(actual, expected, "$")).isNull();
    }

    @Test
    void buildImpactMatches() throws Exception {
        List<DealRecord> all = seededDeals();
        ImpactData impact = HistoryEngine.buildImpact(all, List.of(), buyImpact());

        JsonNode expected = golden().get(1).get("output");
        JsonNode actual = TREE.valueToTree(impact);
        assertThat(firstDiff(actual, expected, "$")).isNull();
    }
}
