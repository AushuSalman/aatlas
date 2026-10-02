package com.aatlas.supplymodel.internal;

import java.util.Map;

/**
 * One supplier as stored beside the models: its record after its last order (what a forecast
 * starts from) and how the training run scored it. Stored as jsonb keyed by the supplier's id;
 * the date is an ISO string so the column needs no date module.
 */
record SupplierRecord(
        String supplierId,
        String name,
        String country,
        int orders,
        int late,
        double lateRate,
        double meanSlip,
        double meanActual,
        double meanPromised,
        double meanQty,
        String lastOrder,
        Map<String, Double> promisedByItem,
        int holdoutOrders,
        Double maeModel,
        Double maeBaseline,
        Double brierModel,
        Double brierBaseline,
        boolean beatsLead,
        boolean beatsLate,
        boolean usable,
        double predictedSlip,
        double predictedLateProb,
        String note) {
}
