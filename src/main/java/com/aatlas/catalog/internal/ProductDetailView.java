package com.aatlas.catalog.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * One item with everything the product page opens on: the row, where its commodity is
 * heading, and which branches have history for it (so the branch picker can tag the rest
 * "no sales of this item" without a second request).
 */
@Schema(name = "ProductDetail")
record ProductDetailView(
        String itemNumber,
        String value,
        String label,
        String description,
        String defaultTenant,
        boolean hasSales,
        boolean hasPrice,
        String shortName,
        String category,
        String subcategory,
        String commodity,
        String unit,
        @Schema(description = "The frontend's COMMODITY_TREND entry for this item's commodity.")
                CommodityTrendView commodityTrend,
        @Schema(description = "Branch codes with sales history for this item. Empty when hasSales is false.")
                List<String> storeIds) {

    /** Where the commodity is heading over the next quarter, as a percent move in input cost. */
    @Schema(name = "CommodityTrend")
    record CommodityTrendView(@Schema(example = "6.4") BigDecimal pct90,
            @Schema(example = "Copper up on the index") String label) {

        /** The frontend's fallback for an unknown commodity. */
        static final CommodityTrendView NONE = new CommodityTrendView(BigDecimal.ZERO, "No commodity exposure");
    }

    static ProductDetailView of(ProductEntity product, boolean hasPrice, CommodityTrendView trend,
            List<String> storeIds) {
        ProductView row = ProductView.of(product, hasPrice);
        return new ProductDetailView(
                row.itemNumber(),
                row.value(),
                row.label(),
                row.description(),
                row.defaultTenant(),
                row.hasSales(),
                row.hasPrice(),
                row.shortName(),
                row.category(),
                row.subcategory(),
                row.commodity(),
                row.unit(),
                trend,
                storeIds);
    }
}
