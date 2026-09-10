package com.aatlas.insights.internal;

import com.aatlas.common.seed.Seeded;
import com.aatlas.insights.internal.LogisticsEngine.Lane;
import com.aatlas.insights.internal.PricingEngine.PricingModel;
import java.util.ArrayList;
import java.util.List;

/**
 * A port of {@code platform/api.ts}'s {@code buildBuyRecommendation}, trimmed to the
 * fields {@code geo.ts}, {@code demographics.ts} and {@code overview.ts} read: the
 * incumbent supplier, the saving against a target cost, and the landed quote panel.
 * Dropped: the calc-steps/weights UI arrays and the "what if this moved supplier" override
 * path - every call site in this module passes a null supplier id, so the row being priced
 * and the incumbent are always the same row.
 *
 * <p>Not a stand-in: {@code buildBuyRecommendation} composes wave 1's supplier and
 * catalogue rows with this module's own pricing/logistics ports, so it needs no
 * cross-track interface - see the wave-2 brief ("supplier priced above market ... no
 * stand-in needed").
 */
final class BuyEngine {

    private BuyEngine() {
    }

    record SupplierQuote(String supplierId, String name, String country, double unitCost, int totalLeadDays, boolean isIncumbent) {
    }

    record BuyRecommendation(
            String itemNumber,
            String incumbentSupplierId,
            String incumbentSupplierName,
            double incumbentCost,
            double targetCost,
            double savingPerUnit,
            double savingPct,
            int annualUnits,
            double annualSaving,
            List<SupplierQuote> quotes) {
    }

    static SupplierRef currentSupplierFor(String item, List<SupplierRef> suppliers) {
        List<SupplierRef> ranked = new ArrayList<>(suppliers);
        ranked.sort((a, b) -> Double.compare(a.priceIndex(), b.priceIndex()));
        int half = (int) Math.ceil(ranked.size() / 2.0);
        List<SupplierRef> pool = Seeded.rand(item, "inc-tier") > 0.3
                ? ranked.subList(0, half)
                : ranked.subList(half, ranked.size());
        return Seeded.pick(item, "current-sup", pool);
    }

    static BuyRecommendation compute(String itemNumber, String destinationStoreCode, CatalogSnapshot snapshot) {
        ProductRef product = snapshot.product(itemNumber)
                .orElseThrow(() -> new IllegalStateException("Unknown item " + itemNumber));
        String defaultTenant = product.defaultStoreCode();
        PricingModel m = PricingEngine.compute(itemNumber, defaultTenant, snapshot);
        LogisticsLaneRef region = LogisticsEngine.regionForStore(destinationStoreCode, snapshot);

        SupplierRef incumbent = currentSupplierFor(itemNumber, snapshot.suppliers());
        double base = m.cost();

        List<SupplierQuote> quotes = new ArrayList<>();
        for (SupplierRef s : snapshot.suppliers()) {
            double exWorks = Fmt.round2(
                    base * (s.priceIndex() / 100) * Seeded.randRange(itemNumber + ":" + s.id(), "q", 0.94, 1.05));
            Lane lane = LogisticsEngine.laneFor(s.country(), region, snapshot);
            double freight = Fmt.round2(exWorks * (lane.freightPct() / 100));
            double duty = Fmt.round2(exWorks * (lane.dutyPct() / 100));
            double unitCost = Fmt.round2(exWorks + freight + duty);
            int totalLeadDays = s.leadTimeDays() + lane.transitDays();
            boolean isIncumbent = s.id().equals(incumbent.id());
            quotes.add(new SupplierQuote(s.id(), s.name(), s.country(), unitCost, totalLeadDays, isIncumbent));
        }
        quotes.sort((a, b) -> Double.compare(a.unitCost(), b.unitCost()));

        double marketLow = quotes.get(0).unitCost();
        double marketHigh = quotes.get(quotes.size() - 1).unitCost();
        double marketMedian = Fmt.round2(quotes.get(quotes.size() / 2).unitCost());

        SupplierQuote incumbentQuote = quotes.stream().filter(SupplierQuote::isIncumbent).findFirst()
                .orElse(quotes.get(0));
        // The supplier being priced is always the incumbent in this module's call sites
        // (the override path - "what if this moved?" - is Buy-screen only).
        double currentCost = incumbentQuote.unitCost();
        double incumbentCost = currentCost;

        double rawTarget = Fmt.round2(marketLow + (marketMedian - marketLow) * 0.35);
        double targetCost = Fmt.round2(Math.min(currentCost, Math.max(rawTarget, marketLow)));
        double savingPerUnit = Fmt.round2(currentCost - targetCost);

        String key = itemNumber + "|" + incumbent.id() + "|" + destinationStoreCode;
        int annualUnits = Seeded.randInt(key, "units", 240, 5200);
        double savingPct = currentCost != 0 ? Fmt.round2((savingPerUnit / currentCost) * 100) : 0;
        double annualSaving = Fmt.round2(savingPerUnit * annualUnits);

        return new BuyRecommendation(itemNumber, incumbent.id(), incumbent.name(), incumbentCost, targetCost,
                savingPerUnit, savingPct, annualUnits, annualSaving, List.copyOf(quotes));
    }
}
