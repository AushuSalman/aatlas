package com.aatlas.analytics.internal.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link LedgerBuilder}/{@link BuyAnalyticsEngine}/{@link DateRanges} against
 * {@code golden/procurement-analytics.json}, produced by running the TypeScript prototype's
 * own {@code resolveRange}, {@code computeBuyAnalytics} and {@code PURCHASE_ORDERS.slice}
 * against the fixture clock 2026-09-01.
 *
 * <p>Pure and Spring-free: builds the ledger in memory exactly as
 * {@code ProcurementLedgerSeedListener} would, then runs the read-side engine over it - no
 * database involved, so a drift anywhere in the port fails here before it reaches a screen.
 */
class ProcurementAnalyticsGoldenTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final ObjectMapper TREE = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static List<JsonNode> golden() throws Exception {
        try (InputStream in = ProcurementAnalyticsGoldenTest.class.getResourceAsStream("/golden/procurement-analytics.json")) {
            assertThat(in).as("golden/procurement-analytics.json on the test classpath").isNotNull();
            JsonNode root = PLAIN.readTree(in);
            return java.util.stream.StreamSupport.stream(root.spliterator(), false).toList();
        }
    }

    /** Treats {@code 4} and {@code 4.0} as equal (JSON literal vs. Java double serialisation) - see SupplierEngineGoldenTest. */
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
                java.util.Set<String> an = new java.util.TreeSet<>();
                a.fieldNames().forEachRemaining(an::add);
                java.util.Set<String> bn = new java.util.TreeSet<>();
                b.fieldNames().forEachRemaining(bn::add);
                an.removeAll(java.util.Set.copyOf(bn));
                java.util.Set<String> onlyB = new java.util.TreeSet<>();
                b.fieldNames().forEachRemaining(onlyB::add);
                java.util.Set<String> bn2 = new java.util.TreeSet<>();
                a.fieldNames().forEachRemaining(bn2::add);
                onlyB.removeAll(bn2);
                return path + ": size actual=" + a.size() + " expected=" + b.size()
                        + " onlyInActual=" + an + " onlyInExpected=" + onlyB;
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

    private static List<PoRow> ledger() {
        return LedgerBuilder.build(TODAY);
    }

    @Test
    void resolveRange90dMatches() throws Exception {
        DateRange r = DateRanges.resolveRange("90d", TODAY);
        JsonNode expected = golden().get(0).get("output");
        JsonNode actual = TREE.valueToTree(r);
        assertThat(firstDiff(actual, expected, "$")).isNull();
    }

    @Test
    void resolveRange12mMatches() throws Exception {
        DateRange r = DateRanges.resolveRange("12m", TODAY);
        JsonNode expected = golden().get(2).get("output");
        JsonNode actual = TREE.valueToTree(r);
        assertThat(firstDiff(actual, expected, "$")).isNull();
    }

    @Test
    void computeBuyAnalytics90dMatches() throws Exception {
        List<PoRow> allRows = ledger();
        DateRange r = DateRanges.resolveRange("90d", TODAY);
        BuyAnalytics a = BuyAnalyticsEngine.compute(r, Filters.none(), allRows);
        JsonNode expected = golden().get(1).get("output");
        JsonNode actual = TREE.valueToTree(a);
        assertThat(firstDiff(actual, expected, "$")).isNull();
    }

    @Test
    void computeBuyAnalytics12mMatches() throws Exception {
        List<PoRow> allRows = ledger();
        DateRange r = DateRanges.resolveRange("12m", TODAY);
        BuyAnalytics a = BuyAnalyticsEngine.compute(r, Filters.none(), allRows);
        JsonNode expected = golden().get(3).get("output");
        JsonNode actual = TREE.valueToTree(a);
        assertThat(firstDiff(actual, expected, "$")).isNull();
    }

    @Test
    void first50LedgerRowsMatch() throws Exception {
        List<PoRow> allRows = ledger();
        List<PoRow> first50 = allRows.subList(0, 50);
        JsonNode expected = golden().get(4).get("output");
        JsonNode actual = TREE.valueToTree(first50.stream().map(ProcurementAnalyticsGoldenTest::wireRow).toList());
        assertThat(firstDiff(actual, expected, "$")).isNull();
    }

    /** The wire shape of {@code PurchaseOrder}: {@code id} is the po-number string, {@code seq} is not on the frontend type. */
    private static java.util.Map<String, Object> wireRow(PoRow r) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.id());
        m.put("date", r.date());
        m.put("supplierId", r.supplierId());
        m.put("supplierName", r.supplierName());
        m.put("country", r.country());
        m.put("itemNumber", r.itemNumber());
        m.put("description", r.description());
        m.put("category", r.category());
        m.put("branchId", r.branchId());
        m.put("branchName", r.branchName());
        m.put("regionKey", r.regionKey());
        m.put("regionLabel", r.regionLabel());
        m.put("qty", r.qty());
        m.put("exWorks", r.exWorks());
        m.put("freight", r.freight());
        m.put("duty", r.duty());
        m.put("landed", r.landed());
        m.put("baseline", r.baseline());
        m.put("target", r.target());
        m.put("followed", r.followed());
        m.put("spend", r.spend());
        m.put("baselineSpend", r.baselineSpend());
        m.put("saved", r.saved());
        m.put("leaked", r.leaked());
        m.put("status", r.status());
        m.put("promisedDays", r.promisedDays());
        m.put("actualDays", r.actualDays());
        m.put("daysLate", r.daysLate());
        m.put("onTime", r.onTime());
        m.put("promisedDate", r.promisedDate());
        m.put("receivedDate", r.receivedDate());
        return m;
    }
}
