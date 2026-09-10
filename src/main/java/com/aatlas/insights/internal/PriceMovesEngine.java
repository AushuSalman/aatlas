package com.aatlas.insights.internal;

import com.aatlas.insights.internal.PricingEngine.PricingModel;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /insights/price-moves}: the biggest expected 90-day price moves in scope,
 * ranked by size. Not one of the four golden-pinned engines this module owns; it reuses
 * {@link GeoEngine#priceDriftPct90} (itself a direct port of {@code intel/sell.ts}'s
 * {@code priceDriftPct90}, which {@code geo.ts} already calls for exactly this figure)
 * rather than reaching into the {@code sell} module's forecast screen, per the wave-2
 * brief's fallback for this endpoint.
 */
final class PriceMovesEngine {

    private PriceMovesEngine() {
    }

    record PriceMove(
            String itemNumber, String name, String category, String storeId, String storeLabel,
            double currentPrice, double driftPct90, double commodityPct90) {
    }

    static List<PriceMove> compute(String region, String storeId, String category, CatalogSnapshot snapshot) {
        List<StoreRef> stores = storeId != null
                ? snapshot.store(storeId).map(List::of).orElse(List.of())
                : snapshot.allStoresOrdered().stream()
                        .filter(s -> region == null || snapshot.marketRegionForState(s.state()).key().equals(region))
                        .toList();

        List<PriceMove> moves = new ArrayList<>();
        for (StoreRef store : stores) {
            for (ProductRef p : snapshot.sellableProducts()) {
                if (category != null && !p.category().equals(category)) {
                    continue;
                }
                if (!snapshot.priceable(p.itemNumber(), store.storeCode())) {
                    continue;
                }
                PricingModel m = PricingEngine.compute(p.itemNumber(), store.storeCode(), snapshot);
                double drift = GeoEngine.priceDriftPct90(m, p, snapshot);
                moves.add(new PriceMove(p.itemNumber(), p.shortName(), p.category(), store.storeCode(),
                        GeoEngine.storeLabel(store.storeCode(), snapshot), m.currentPrice(), drift,
                        snapshot.commodity(p.commodity()).pct90AsDouble()));
            }
        }
        return moves.stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.driftPct90()), Math.abs(a.driftPct90())))
                .limit(20)
                .toList();
    }
}
