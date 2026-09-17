package com.aatlas.insights.internal;

import com.aatlas.history.PricingMath;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /insights/price-moves}: the tenant's biggest expected 90-day price moves,
 * {@code PricingMath.driftPct90} (reference commodity trend plus the pair's own demand
 * signal) over every priceable pair in scope, ranked by size. {@code commoditySource} is
 * always {@code reference} - the commodity half of the drift is never a live quote.
 */
final class PriceMovesEngine {

    private PriceMovesEngine() {
    }

    record PriceMove(
            String itemNumber, String name, String category, String storeId, String storeLabel,
            Double currentPrice, double driftPct90, Double commodityPct90, String commoditySource) {
    }

    static List<PriceMove> compute(String region, String storeId, String category, InsightsData data) {
        List<PairFacts> pairs = storeId != null ? data.atStore(storeId) : data.pairsByKey().values().stream().toList();

        List<PriceMove> moves = new ArrayList<>();
        for (PairFacts f : pairs) {
            if (!f.priceable()) {
                continue;
            }
            if (category != null && !category.equals(f.pair().category())) {
                continue;
            }
            if (storeId == null && region != null && !region.equals(f.pair().regionKey())) {
                continue;
            }
            double movePercent = f.demand() == null ? 0 : f.demand().movePercent();
            double drift = PricingMath.driftPct90(f.commodityPct90(), movePercent).doubleValue();
            moves.add(new PriceMove(f.pair().itemNumber(), f.pair().shortName(), f.pair().category(), f.storeKey(),
                    f.pair().storeLabel(), Fmt.dv(f.currentPrice()), Fmt.round1(drift), Fmt.dv(f.commodityPct90()),
                    "reference"));
        }
        return moves.stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.driftPct90()), Math.abs(a.driftPct90())))
                .limit(20)
                .toList();
    }
}
