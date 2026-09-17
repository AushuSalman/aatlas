package com.aatlas.ingest.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * What data the workspace has, what each feature can do with it, and what to load next.
 *
 * <p>Every figure is a count over the tenant's own rows; nothing here is estimated.
 */
@Schema(name = "Readiness")
record ReadinessView(
        SourceInfo dataSource,
        CatalogueInfo catalogue,
        SalesInfo sales,
        PurchasesInfo purchases,
        PricesInfo prices,
        InventoryInfo inventory,
        CompetitorInfo competitorPrices,
        SuppliersInfo suppliers,
        List<Feature> features,
        List<NextStep> nextSteps,
        SampleLoading sampleLoading) {

    record SourceInfo(String kind, String label) {
    }

    record CatalogueInfo(int products, int stores, int customers, int storesUnassigned, int customersUnassigned) {
    }

    record SalesInfo(long rows, int months, LocalDate earliest, LocalDate latest, int items, int stores,
            long withCustomer) {
    }

    record PurchasesInfo(long rows, int months, LocalDate earliest, LocalDate latest, int suppliers, int items,
            long received) {
    }

    record PricesInfo(int itemsWithPrice, int itemsWithCost, int itemsMissingPrice, int itemsMissingCost,
            int quotesOnFile) {
    }

    record InventoryInfo(int items, int stores, LocalDate asOf) {
    }

    record CompetitorInfo(int items, long observations, int competitors) {
    }

    record SuppliersInfo(int count, int withTerms, int withPurchases) {
    }

    /** @param status {@code ready | partial | locked}; {@code needs} names the kinds when not ready */
    record Feature(String key, String label, String status, List<String> needs, String detail) {
    }

    record NextStep(String key, String title, String detail, String href, String kind) {
    }

    record SampleLoading(boolean active, List<SampleBatch> batches) {
    }

    record SampleBatch(String kind, String status, String failureReason) {
    }
}
