package com.aatlas.buy.internal;

import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.SalesHistory;
import com.aatlas.history.SalesStats;
import com.aatlas.history.Window;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Spec-A S2 "buy" annualUnits/annualVolume: {@code PurchaseHistory.item(W12).units} at the
 * branch first, else any branch, else {@code SalesHistory.item(W12).units} the same way. Used
 * by both the landed-cost panel and buy intel so a branch's volume is one number wherever it
 * appears, and null (never zero) when the tenant has neither purchase nor sales history for
 * the item.
 */
record AnnualUnits(BigDecimal units, String source) {

    static final AnnualUnits NONE = new AnnualUnits(null, "purchases");

    static AnnualUnits forStore(PurchaseHistory purchases, SalesHistory sales, String itemNumber, UUID productId,
            UUID storeId, LocalDate today) {
        Window w12 = Window.trailingMonths(today, 12);
        if (itemNumber != null) {
            PurchaseHistory.PoStats atStore = purchases.itemAtStore(itemNumber, storeId, w12);
            if (atStore.any()) {
                return new AnnualUnits(atStore.units(), "purchases");
            }
            PurchaseHistory.PoStats anyBranch = purchases.item(itemNumber, w12).all();
            if (anyBranch.any()) {
                return new AnnualUnits(anyBranch.units(), "purchases");
            }
        }
        if (productId != null) {
            SalesStats atStoreSales = sales.itemStore(productId, storeId, w12);
            if (atStoreSales.any()) {
                return new AnnualUnits(atStoreSales.units(), "sales");
            }
            SalesStats itemSales = sales.item(productId, w12);
            if (itemSales.any()) {
                return new AnnualUnits(itemSales.units(), "sales");
            }
        }
        return NONE;
    }
}
