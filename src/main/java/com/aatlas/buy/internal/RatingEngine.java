package com.aatlas.buy.internal;

import com.aatlas.common.seed.Seeded;

/**
 * The star rating a supplier would carry on the supplier panel - derived, per the frontend's
 * {@code intel/suppliers.ts}, from the same facts the Buy screen already shows (on-time
 * record, lead time and stock position, price index, defect rate) plus one seeded
 * communication score.
 *
 * <p>{@code buy2.ts}'s {@code supplierRisk} (its "Buyer rating" factor) and {@code
 * scoreSuppliers} (its relationship sub-score) both read a supplier's rating from {@code
 * suppliers.ts}'s {@code supplierProfile}, so this small slice of that engine is ported here
 * too - the same stand-in situation as {@link TermsEngine} and {@link RiskEngine}: the
 * {@code suppliers} module has this logic already but exposes no public reader for it.
 *
 * <p>TODO(merge): consider depending on suppliers' public reader if one exists after merge.
 */
final class RatingEngine {

    private RatingEngine() {
    }

    private static final String COMM_SALT = "rating-comm-78";

    /** On-time record to stars: 97% is five, 82% three, 67% one. */
    static double otifStars(double otif) {
        return Js.clamp(5 - (97 - otif) / 7.5, 1, 5);
    }

    /** Order-to-dock days to stars: a week is five, six weeks one. */
    static double leadStars(double days) {
        return Js.clamp(5 - ((days - 7) / 35.0) * 4, 1, 5);
    }

    /** Typical inbound transit for the country, before the inland leg; 30 days if unknown. */
    static int inboundDays(CatalogGateway.LogisticsRef ref, String country) {
        CatalogGateway.Origin o = ref.origins().get(country);
        return o == null ? 30 : o.inboundDays();
    }

    /**
     * Whether the supplier ships from stock or will rush - the same two seeds the buy
     * engine's route model reads ({@code route:<id>}/{@code stkD} and {@code
     * srisk:<id>}/{@code cap}), so this and a route's "from stock" label can never disagree.
     */
    static boolean holdsStock(String id) {
        return Seeded.rand("route:" + id, "stkD") > 0.42 && Seeded.rand("srisk:" + id, "cap") > 0.22;
    }

    private static double speedStars(CatalogGateway.LogisticsRef ref, String id, String country, int leadTimeDays) {
        double own = leadStars(leadTimeDays + inboundDays(ref, country));
        if (!holdsStock(id)) {
            return own;
        }
        double bump = "USA".equals(country) ? 5 : "Mexico".equals(country) ? 4.6 : 4.2;
        return Math.max(own, bump);
    }

    /** Delivery as a buyer experiences it: the on-time record, and how fast the goods can be here. */
    static double deliveryStars(CatalogGateway.LogisticsRef ref, String id, String country, int leadTimeDays, double otif) {
        return Js.round1(0.55 * otifStars(otif) + 0.45 * speedStars(ref, id, country, leadTimeDays));
    }

    /** Defect rate to stars: 0.2% is five, 3.4% is three. */
    static double qualityStars(double defectPct) {
        return Js.clamp(Js.round1(5 - ((defectPct - 0.2) / 3.2) * 2), 3, 5);
    }

    /** Price index to stars: 89 (eleven under market) is five, 114 is three. */
    static double pricingStars(double priceIndex) {
        return Js.clamp(Js.round1(5 - ((priceIndex - 89) / 25.0) * 2), 3, 5);
    }

    static double communicationStars(String key) {
        return Js.round1(Seeded.randRange(key, COMM_SALT, 1, 5));
    }

    /** Delivery carries the most weight: for a distributor, "did it arrive when promised" is the review. */
    static double overall(double quality, double delivery, double communication, double pricing) {
        return Js.clamp(Js.round1(quality * 0.15 + delivery * 0.4 + communication * 0.3 + pricing * 0.15), 1, 5);
    }

    static String ratingLabel(double rating) {
        return rating >= 4.5 ? "Excellent" : rating >= 4 ? "Good" : rating >= 3.3 ? "Fair" : "Weak";
    }

    static int reviewCount(String key) {
        return Seeded.randInt(key, "rating-n", 40, 380);
    }

    /** The same seeded defect rate the buy engine shows for this supplier - same key, same salt. */
    static double seededDefect(String id) {
        return Js.round1(Seeded.randRange("sup:" + id, "defect", 0.2, 3.4));
    }

    record Profile(double rating, int reviewCount) {
    }

    static Profile profileFor(CatalogGateway.LogisticsRef ref, String id, String country, int leadTimeDays,
            double otifPct, double priceIndex, double defectPct) {
        String key = "sup:" + id;
        double quality = qualityStars(defectPct);
        double delivery = deliveryStars(ref, id, country, leadTimeDays, otifPct);
        double communication = communicationStars(key);
        double pricing = pricingStars(priceIndex);
        double rating = overall(quality, delivery, communication, pricing);
        return new Profile(rating, reviewCount(key));
    }
}
