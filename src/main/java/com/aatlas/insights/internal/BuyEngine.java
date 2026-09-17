package com.aatlas.insights.internal;

import com.aatlas.history.PricingMath;
import com.aatlas.history.PurchaseHistory;
import com.aatlas.history.PurchaseHistory.PoStats;
import com.aatlas.history.Reference;
import com.aatlas.history.Suppliers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Spec 3.5's buy target cost, over the tenant's real purchase history: the incumbent supplier
 * ({@code PurchaseHistory.incumbent}), the ladder cost, and a target built from the landed
 * cost of every supplier with a quote on file ({@code Suppliers.panelFor}'s ex-works, landed
 * through that supplier's own freight/duty lane). Absent purchase history for the item ->
 * empty (the caller locks {@code purchases}), never a fabricated saving.
 */
@Component
class BuyEngine {

    record BuyRecommendation(
            String itemNumber, String incumbentSupplierId, String incumbentSupplierName,
            BigDecimal incumbentCost, BigDecimal targetCost, BigDecimal savingPerUnit, BigDecimal savingPct,
            BigDecimal annualUnits, BigDecimal annualSaving, int quotedSuppliers) {
    }

    private final PurchaseHistory purchaseHistory;
    private final Suppliers suppliers;
    private final Reference reference;

    BuyEngine(PurchaseHistory purchaseHistory, Suppliers suppliers, Reference reference) {
        this.purchaseHistory = purchaseHistory;
        this.suppliers = suppliers;
        this.reference = reference;
    }

    Optional<BuyRecommendation> compute(PairFacts facts, LocalDate today) {
        PoStats purchases12m = facts.pair().purchases12m();
        if (purchases12m == null || !purchases12m.any()) {
            return Optional.empty();
        }
        String itemNumber = facts.pair().itemNumber();
        BigDecimal currentCost = facts.cost() != null ? facts.cost() : purchases12m.avgLanded();

        Optional<PurchaseHistory.SupplierShare> incumbent = purchaseHistory.incumbent(itemNumber, today);

        List<BigDecimal> landedQuotes = new ArrayList<>();
        for (Suppliers.SupplierLink link : suppliers.panelFor(facts.pair().productId())) {
            if (link.exWorks() == null) {
                continue;
            }
            Reference.Origin origin = reference.origin(link.supplier().country());
            BigDecimal multiplier = BigDecimal.ONE
                    .add(origin.inboundPct().divide(PricingMath.HUNDRED, 6, PricingMath.ROUNDING))
                    .add(origin.dutyPct().divide(PricingMath.HUNDRED, 6, PricingMath.ROUNDING));
            landedQuotes.add(link.exWorks().multiply(multiplier).setScale(4, PricingMath.ROUNDING));
        }
        landedQuotes.sort(BigDecimal::compareTo);
        int n = landedQuotes.size();

        BigDecimal targetCost;
        if (n >= 2) {
            BigDecimal low = landedQuotes.get(0);
            BigDecimal median = landedQuotes.get(n / 2);
            BigDecimal raw = low.add(median.subtract(low).multiply(new BigDecimal("0.35")));
            targetCost = PricingMath.min(currentCost, raw);
        } else if (n == 1) {
            targetCost = PricingMath.min(currentCost, landedQuotes.get(0));
        } else {
            targetCost = null;
        }

        BigDecimal savingPerUnit = currentCost != null && targetCost != null
                ? PricingMath.max(BigDecimal.ZERO, currentCost.subtract(targetCost))
                : null;
        BigDecimal annualUnits = purchases12m.units() == null ? BigDecimal.ZERO : purchases12m.units();
        BigDecimal annualSaving = savingPerUnit == null ? null : savingPerUnit.multiply(annualUnits);
        BigDecimal savingPct = savingPerUnit != null && currentCost != null && currentCost.signum() > 0
                ? PricingMath.pct(savingPerUnit, currentCost)
                : null;

        String supplierId = incumbent.map(PurchaseHistory.SupplierShare::supplierKey).orElse(null);
        String supplierName = incumbent.map(PurchaseHistory.SupplierShare::name).orElse(null);

        return Optional.of(new BuyRecommendation(itemNumber, supplierId, supplierName, currentCost, targetCost,
                savingPerUnit, savingPct, annualUnits, annualSaving, n));
    }
}
