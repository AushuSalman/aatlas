package com.aatlas.supplymodel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The tenant's delivery model, as the rest of the API reads it: for a supplier, item, branch
 * and quantity, the lead time to expect and the chance the order is late; the model's status
 * with its per-supplier report; and training on demand.
 *
 * <p>Every answer carries whether it may be used. A supplier is {@code usable} only when the
 * model beat the supplier's own trailing record on the held-out orders, on lead time or on
 * lateness; a caller that gets {@code usable == false} should fall back to the supplier's
 * record, which is what the Buy screens show today.
 */
public interface DeliveryModels {

    /**
     * @param supplier the supplier master's id, or the supplier's name
     * @param qty the order's quantity; zero or less means a typical order, the supplier's usual quantity
     * @param promisedDays the lead time the supplier quotes for this order; null means the model uses what the
     *        supplier has promised for this item before, else across its orders
     */
    Optional<Forecast> forecast(String supplier, String itemNumber, String storeCode, int qty, Integer promisedDays);

    Status status();

    /** Train now for the current tenant. Only seats that may change the pricing model. */
    Status train();

    /**
     * @param leadDays the days the order is expected to take, promised plus the predicted slip
     * @param lateProbability the chance it arrives after the promised date, 0 to 1
     * @param expectedDaysLate the predicted slip when positive, else 0
     * @param errorDays the model's mean error in days on the supplier's held-out orders
     * @param baselineErrorDays the same for the supplier's trailing record
     * @param brier the model's Brier score on late-or-not over the held-out orders (lower is better, 0.25 is a coin)
     */
    record Forecast(
            BigDecimal leadDays,
            BigDecimal lateProbability,
            BigDecimal expectedDaysLate,
            BigDecimal promisedDays,
            boolean usable,
            int orders,
            BigDecimal errorDays,
            BigDecimal baselineErrorDays,
            BigDecimal brier,
            BigDecimal baselineBrier,
            Instant trainedAt,
            String note) {
    }

    /** One supplier as the training run scored it, with its record. */
    record SupplierReport(
            String supplierId,
            String name,
            String country,
            int orders,
            int late,
            BigDecimal lateRate,
            BigDecimal avgPromisedDays,
            BigDecimal avgActualDays,
            int holdoutOrders,
            BigDecimal errorDays,
            BigDecimal baselineErrorDays,
            BigDecimal brier,
            BigDecimal baselineBrier,
            boolean beatsLeadTime,
            boolean beatsLateness,
            boolean usable,
            BigDecimal predictedLeadDays,
            BigDecimal predictedLateProbability,
            String note) {
    }

    record Status(
            boolean trained,
            Instant trainedAt,
            int orders,
            int suppliers,
            int suppliersUsable,
            LocalDate from,
            LocalDate to,
            int holdoutOrders,
            long trainMillis,
            String note,
            /** Orders received or updated after the last training run: the next run picks them up. */
            int ordersSinceTrained,
            List<SupplierReport> report) {
    }
}
