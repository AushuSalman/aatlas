package com.aatlas.realdata;

import com.aatlas.common.csv.CsvReader;
import com.aatlas.history.Window;
import com.aatlas.ingest.internal.SampleDates;
import com.aatlas.ingest.internal.csv.ValueParsers;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hardin sample as the integration tests read it: the classpath CSVs summed in Java,
 * dates shifted exactly as the sample loader shifts them, rows after today dropped.
 *
 * <p>Every number an IT pins against the API comes from here, so the tests never encode a
 * constant that drifts with the generator or the calendar.
 */
public final class SampleOracle {

    public record Sale(String item, String store, LocalDate date, BigDecimal qty, BigDecimal price, BigDecimal cost,
            String customer) {
    }

    public record Purchase(String poRef, LocalDate orderDate, String supplier, String item, int qty,
            BigDecimal exWorks, BigDecimal landed, String shipTo, LocalDate received) {
    }

    public record Band(BigDecimal low, BigDecimal high) {
    }

    private final LocalDate today;
    private final int offsetDays;
    private final List<Sale> sales;
    private final List<Purchase> purchases;

    private SampleOracle(LocalDate today, List<Sale> sales, List<Purchase> purchases) {
        this.today = today;
        this.offsetDays = SampleDates.offsetDays(today);
        this.sales = sales;
        this.purchases = purchases;
    }

    public static SampleOracle load(LocalDate today) {
        int shift = SampleDates.offsetDays(today);
        List<Sale> sales = new ArrayList<>();
        for (List<String> r : rows("/samples/sales-history.csv")) {
            LocalDate date = ValueParsers.parseDate(r.get(2)).orElseThrow().plusDays(shift);
            if (date.isAfter(today)) {
                continue;
            }
            sales.add(new Sale(r.get(0), r.get(7), date, new BigDecimal(r.get(3)), new BigDecimal(r.get(4)),
                    new BigDecimal(r.get(5)), r.get(6)));
        }
        List<Purchase> purchases = new ArrayList<>();
        for (List<String> r : rows("/samples/purchase-history.csv")) {
            LocalDate order = ValueParsers.parseDate(r.get(1)).orElseThrow().plusDays(shift);
            if (order.isAfter(today)) {
                continue;
            }
            LocalDate received = r.get(14).isBlank() ? null
                    : ValueParsers.parseDate(r.get(14)).orElseThrow().plusDays(shift);
            purchases.add(new Purchase(r.get(0), order, r.get(2), r.get(4), Integer.parseInt(r.get(6)),
                    new BigDecimal(r.get(7)), new BigDecimal(r.get(10)), r.get(12), received));
        }
        return new SampleOracle(today, sales, purchases);
    }

    public LocalDate today() {
        return today;
    }

    public int offsetDays() {
        return offsetDays;
    }

    public int salesRows() {
        return sales.size();
    }

    public int purchaseRows() {
        return purchases.size();
    }

    public int purchaseRows(LocalDate from, LocalDate to) {
        return (int) purchases.stream().filter(p -> !p.orderDate().isBefore(from) && !p.orderDate().isAfter(to)).count();
    }

    public long receivedRows() {
        return purchases.stream().filter(p -> p.received() != null).count();
    }

    public BigDecimal revenue(Window w) {
        BigDecimal total = BigDecimal.ZERO;
        for (Sale s : sales) {
            if (w.contains(s.date())) {
                total = total.add(s.qty().multiply(s.price()));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** Quantity-weighted average net price of one item at one branch over the window. */
    public BigDecimal weightedPrice(String item, String store, Window w) {
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal units = BigDecimal.ZERO;
        for (Sale s : sales) {
            if (s.item().equals(item) && s.store().equals(store) && w.contains(s.date())) {
                value = value.add(s.qty().multiply(s.price()));
                units = units.add(s.qty());
            }
        }
        return units.signum() == 0 ? null : value.divide(units, 2, RoundingMode.HALF_UP);
    }

    public Band band(String item, String store, Window w) {
        BigDecimal low = null;
        BigDecimal high = null;
        for (Sale s : sales) {
            if (s.item().equals(item) && s.store().equals(store) && w.contains(s.date())) {
                low = low == null || s.price().compareTo(low) < 0 ? s.price() : low;
                high = high == null || s.price().compareTo(high) > 0 ? s.price() : high;
            }
        }
        return low == null ? null : new Band(low, high);
    }

    /** The supplier with the largest landed spend on the item over the window. */
    public String topShareSupplier(String item, Window w) {
        Map<String, BigDecimal> bySupplier = new HashMap<>();
        for (Purchase p : purchases) {
            if (p.item().equals(item) && w.contains(p.orderDate())) {
                bySupplier.merge(p.supplier(), p.landed().multiply(BigDecimal.valueOf(p.qty())), BigDecimal::add);
            }
        }
        return bySupplier.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    public BigDecimal spend(LocalDate from, LocalDate to) {
        BigDecimal total = BigDecimal.ZERO;
        for (Purchase p : purchases) {
            if (!p.orderDate().isBefore(from) && !p.orderDate().isAfter(to)) {
                total = total.add(p.landed().multiply(BigDecimal.valueOf(p.qty())));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public List<Sale> sales() {
        return sales;
    }

    public List<Purchase> purchases() {
        return purchases;
    }

    private static List<List<String>> rows(String resource) {
        try (InputStream in = SampleOracle.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the classpath");
            }
            List<List<String>> all = CsvReader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return all.subList(1, all.size());
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read " + resource, ex);
        }
    }
}
