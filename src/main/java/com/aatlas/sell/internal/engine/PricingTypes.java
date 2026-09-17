package com.aatlas.sell.internal.engine;

import com.aatlas.history.PricingMath;
import com.aatlas.history.SalesHistory.Bucket;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The real (item, store) pricing picture, built from {@code history} rather than a hash.
 * Every money/ratio field is a nullable {@link BigDecimal}: {@code null} means the input was
 * absent, and the caller either skips the dependent step or adds the section key to
 * {@code locked}. Kept package-private: {@link com.aatlas.sell.internal.dto} converts what a
 * response actually carries at the boundary.
 */
public final class PricingTypes {

    private PricingTypes() {
    }

    record Competitor(String name, String domain, BigDecimal price, BigDecimal deltaVsCurrent) {
    }

    /** Mirrors {@code com.aatlas.history.PricingMath.Demand} with the two extra display fields the UI wants. */
    record DemandModel(
            String level,
            String label,
            BigDecimal index,
            BigDecimal movePercent,
            String confidence,
            BigDecimal confWeight,
            BigDecimal recentVelocity,
            BigDecimal expectedVelocity,
            int maxAdjustmentPct,
            String trendDirection,
            int historyDays) {
    }

    /**
     * Everything the sell engines need about one (item, store) pair, resolved once.
     *
     * @param priceable {@code history.PriceLadder.currentPrice} resolves to something &gt; 0
     * @param cost nullable; {@code costSource} one of {@code Resolved}'s labels
     * @param currentPrice nullable
     * @param ownRef the pair's own last price (itemStore W12), nullable
     * @param anchorValue the market anchor (competitor &gt; peer &gt; benchmark &gt; history), nullable
     * @param optimalPrice/aggressivePrice null when neither an anchor nor an own reference exists
     * @param beta own-price elasticity; never null (defaults to -1.2, basis {@code default})
     * @param locked section keys this pair is missing an input for
     */
    public record PricingModel(
            String item, UUID productId, String storeId, UUID storeUuid, boolean priceable,

            BigDecimal cost, String costSource, LocalDate costAsOf,
            BigDecimal currentPrice, String currentPriceSource, LocalDate currentPriceAsOf,
            BigDecimal ownRef,
            BigDecimal anchorValue, String anchorSource, int anchorObservations,

            BigDecimal optimalPrice, BigDecimal aggressivePrice, String recommendedTier,
            BigDecimal floorPrice, BigDecimal ceilingPrice,

            BigDecimal peerQ1, BigDecimal peerQ2, BigDecimal peerQ3, int peerStores,
            List<Competitor> competitors, BigDecimal competitorMedian,

            BigDecimal msaMult, String msaMode, BigDecimal rpp,

            DemandModel demand,

            String segment,
            long totalTransactions, int totalCompanies,
            BigDecimal observedMin, BigDecimal observedMax,

            BigDecimal beta, BigDecimal betaR2, String elasticityBasis,

            BigDecimal commodityPct90, String commodityLabel, LocalDate commodityAsOf,

            BigDecimal units90, BigDecimal unitsPrior90, BigDecimal units12m, BigDecimal avgPrice30,
            BigDecimal avgPrice90p,

            BigDecimal onHandUnits, LocalDate inventoryAsOf, boolean inventoryStale,

            List<Bucket> buckets, PricingMath.Recommendation recommendation,

            List<String> locked) {

        boolean locked(String key) {
            return locked.contains(key);
        }
    }
}
