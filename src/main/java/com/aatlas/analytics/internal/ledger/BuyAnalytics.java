package com.aatlas.analytics.internal.ledger;

import java.util.List;

/** Everything the Buying insights dashboard draws, for one window and one set of filters. Ports {@code procurement.ts}'s {@code BuyAnalytics}. */
public record BuyAnalytics(
        DateRange range,
        DateRange compare,
        boolean comparable,
        String unit,
        List<PoRow> orders,
        List<PoRow> receipts,
        boolean empty,

        Kpi spend,
        Kpi saved,
        Kpi leaked,
        Kpi costIndex,
        Kpi avgUnitCost,
        Kpi captureRate,
        Kpi onTime,
        Kpi leadDays,
        Kpi orderCount,

        int activeSuppliers,
        int activeSuppliersPrev,
        int lines,
        int units,
        double baselineSpend,
        double savingsRatePct,

        List<TimelinePoint> timeline,
        List<SupplierRow> suppliers,
        List<MixSlice> supplierMix,
        List<MixSlice> categoryMix,
        List<MixSlice> regionMix,
        List<MixSlice> originMix,
        LandedSplit landedSplit,
        List<Opportunity> opportunities,
        Status status,
        List<DeliveryPoint> delivery,
        List<PoRow> lateLines) {

    public record LandedSplit(double exWorks, double freight, double duty) {
    }

    public record Status(int received, int inTransit, int open, double openValue, double inTransitValue) {
    }
}
