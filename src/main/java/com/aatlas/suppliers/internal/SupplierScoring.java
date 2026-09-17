package com.aatlas.suppliers.internal;

import static com.aatlas.suppliers.internal.Js.clamp;
import static com.aatlas.suppliers.internal.Js.round1;

import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stars, derived from what the platform already measures. A port of the star-scoring half of
 * the frontend's {@code intel/suppliers.ts}: the on-time record, the lead time and stock
 * position, the price index and the defect rate it seeds, plus the one seeded component
 * (communication) that the platform cannot otherwise measure.
 *
 * <p>Arithmetic must match the TypeScript to the last digit - two screens must never
 * disagree about a supplier's stars - so every constant, salt and rounding step here is
 * copied verbatim rather than "simplified". {@code SupplierScoringGoldenTest} pins it against
 * {@code golden/suppliers.json}.
 */
final class SupplierScoring {

    private SupplierScoring() {
    }

    // -- Inbound transit, by the supplier's own country (platform/logistics.ts ORIGINS) -----

    private static final Map<String, Integer> INBOUND_DAYS = Map.ofEntries(
            Map.entry("USA", 0),
            Map.entry("Mexico", 6),
            Map.entry("Germany", 21),
            Map.entry("China", 34),
            Map.entry("India", 38),
            Map.entry("Vietnam", 31),
            Map.entry("UK", 19),
            Map.entry("Canada", 3));

    private static final int FALLBACK_INBOUND_DAYS = 30;

    private static int inboundDays(String country) {
        return INBOUND_DAYS.getOrDefault(country, FALLBACK_INBOUND_DAYS);
    }

    // -- Stars from what the platform already knows -----------------------------------------

    /** On-time record to stars: 97% is five, 82% three, 67% one. */
    static double otifStars(double otif) {
        return clamp(5 - (97 - otif) / 7.5, 1, 5);
    }

    /** The inverse: the on-time record a delivery star implies. */
    static double otifFromStars(double stars) {
        return Js.round2(clamp(97 - (5 - stars) * 7.5, 60, 99));
    }

    /** Order-to-dock days to stars: a week is five, six weeks one. */
    static double leadStars(double days) {
        return clamp(5 - ((days - 7) / 35) * 4, 1, 5);
    }

    /**
     * Whether the supplier ships from stock (or will rush). The same two seeds the Buy
     * screen's route model reads, so the star here and the route there can never disagree.
     */
    static boolean holdsStock(String id) {
        return Seeded.rand("route:" + id, "stkD") > 0.42 && Seeded.rand("srisk:" + id, "cap") > 0.22;
    }

    static double speedStars(String id, String country, double leadTimeDays) {
        double own = leadStars(leadTimeDays + inboundDays(country));
        if (!holdsStock(id)) {
            return own;
        }
        double stockFloor = "USA".equals(country) ? 5 : "Mexico".equals(country) ? 4.6 : 4.2;
        return Math.max(own, stockFloor);
    }

    /** Delivery as a buyer experiences it: the on-time record, and how fast the goods can be here. */
    static double deliveryStars(String id, String country, double leadTimeDays, double otif) {
        return round1(0.55 * otifStars(otif) + 0.45 * speedStars(id, country, leadTimeDays));
    }

    /** Price index to stars. A vetted panel sits between three and five: 89 is five, 114 is three. */
    static double pricingStars(double idx) {
        return clamp(round1(5 - ((idx - 89) / 25) * 2), 3, 5);
    }

    static double indexFromStars(double stars) {
        return Js.round2(89 + ((5 - stars) / 2) * 25);
    }

    /** Defect rate to stars, on the same scale: 0.2% is five, 3.4% is three. */
    static double qualityStars(double defect) {
        return clamp(round1(5 - ((defect - 0.2) / 3.2) * 2), 3, 5);
    }

    /** The Buy screen's defect rate for a supplier - same key, same salt, so the two agree. */
    static double seededDefect(String id) {
        return round1(Seeded.randRange("sup:" + id, "defect", 0.2, 3.4));
    }

    /** Delivery carries the most weight: for a distributor, "did it arrive when promised" is the review. */
    static final Map<String, Double> WEIGHTS = Map.of(
            "quality", 0.15, "delivery", 0.4, "communication", 0.3, "pricing", 0.15);

    /** The one seeded component. A new salt for this feature; nothing else reads it. */
    static final String COMM_SALT = "rating-comm-78";

    static double communicationStars(String key) {
        return round1(Seeded.randRange(key, COMM_SALT, 1, 5));
    }

    static double overall(RatingBreakdown b) {
        return clamp(round1(
                b.quality() * WEIGHTS.get("quality") + b.delivery() * WEIGHTS.get("delivery")
                        + b.communication() * WEIGHTS.get("communication") + b.pricing() * WEIGHTS.get("pricing")),
                1, 5);
    }

    static String ratingLabel(double rating) {
        return rating >= 4.5 ? "Excellent" : rating >= 4 ? "Good" : rating >= 3.3 ? "Fair" : "Weak";
    }

    // -- Certifications -----------------------------------------------------------------------

    private static final Map<String, List<String>> CERTS = Map.of(
            "Copper & brass", List.of("ISO 9001", "ASTM B88", "NSF/ANSI 61", "ISO 14001", "UL"),
            "Valves", List.of("ISO 9001", "API 607", "CE PED", "ISO 14001", "UL", "CSA"),
            "Polymers", List.of("ISO 9001", "NSF/ANSI 14", "ASTM F876", "ISO 14001", "UL"),
            "Steel", List.of("ISO 9001", "ASTM A53", "EN 10204 3.1", "ISO 14001", "API 5L"),
            "Tooling", List.of("ISO 9001", "ISO 14001", "CE", "UL", "ANSI B107"),
            "Fittings", List.of("ISO 9001", "ASME B16", "NSF/ANSI 61", "UL", "ISO 14001", "CSA"));

    /** ISO 9001 always; then two to four from the category's list. */
    static List<String> certsFor(String key, String category) {
        List<String> pool = CERTS.get(category);
        int n = Seeded.randInt(key, "certs-n", 2, 4);
        List<String> out = new ArrayList<>();
        out.add(pool.get(0));
        int i = 1 + Seeded.randInt(key, "certs-o", 0, pool.size() - 2);
        while (out.size() < n) {
            String c = pool.get(1 + (i % (pool.size() - 1)));
            if (!out.contains(c)) {
                out.add(c);
            }
            i++;
        }
        return out;
    }

    // -- Reviews ------------------------------------------------------------------------------

    private static final List<String> REVIEWERS = List.of(
            "Purchasing manager, mechanical contractor",
            "Category buyer, plumbing distributor",
            "Branch manager, HVAC wholesaler",
            "Procurement lead, industrial MRO",
            "Owner, plumbing contractor",
            "Buyer, regional distributor",
            "Operations director, building services",
            "Supply chain manager, facilities group");

    private static final List<String> WHEN = List.of(
            "2 weeks ago", "1 month ago", "6 weeks ago", "2 months ago", "3 months ago", "5 months ago",
            "8 months ago", "last year");

    private static final List<String> POSITIVE = List.of(
            "Two years in and not a single short shipment. Their account manager answers the same day.",
            "Certs come with every lot without asking. Pricing moved with the index, never above it.",
            "Handled a rush order over a holiday weekend. Not the cheapest quote we had, but the one that showed up.",
            "Consistent quality across three plants. We stopped incoming inspection on their lines last year.",
            "Straightforward to deal with. Quotes are itemised, freight is quoted up front, no surprises on the invoice.",
            "Lead times quoted are lead times delivered. That alone is worth a few points on price.",
            "Took a spec change mid-order without drama or a re-quote. Rare.");

    private static final List<String> MIXED = List.of(
            "Good product, slow paperwork. Expect to chase the certificate of analysis and the packing list.",
            "Pricing is sharp but the lead time slipped twice this year. Fine for stock replenishment, not for a job with a date.",
            "Solid on standard items; anything non-standard takes weeks to quote.",
            "Delivered what we ordered, though communication went quiet for a week mid-order.",
            "Decent value. Packaging could be better - two dented cartons on the last pallet.");

    private static final List<String> NEGATIVE = List.of(
            "Two of the last five deliveries were a week late with no notice. Had to source elsewhere for a contract job.",
            "Quality has drifted. Rejected a lot for out-of-spec dimensions and it took a month to get the credit.",
            "Cheap for a reason. Invoices did not match the quote and nobody picked up the phone.",
            "Minimum order jumped without warning. Not a partner for a smaller branch.",
            "Three quote requests, one answer. We moved the line.");

    /** Three reviews around the rating: one warmer, one on it, one cooler. Never the same text twice. */
    static List<SupplierReview> reviewsFor(String key, double rating) {
        double[] offsets = {0.6, 0, -1.1};
        Set<String> used = new HashSet<>();
        List<SupplierReview> out = new ArrayList<>(3);
        for (int i = 0; i < offsets.length; i++) {
            int stars = (int) clamp(Math.round(rating + offsets[i]), 1, 5);
            List<String> pool = stars >= 4 ? POSITIVE : stars == 3 ? MIXED : NEGATIVE;
            int idx = (int) Math.floor(Seeded.rand(key, "rv-" + i) * pool.size());
            while (used.contains(pool.get(idx % pool.size()))) {
                idx++;
            }
            String text = pool.get(idx % pool.size());
            used.add(text);
            String author = Seeded.pick(key, "rva-" + i, REVIEWERS);
            String when = WHEN.get(Math.min(WHEN.size() - 1, i * 2 + Seeded.randInt(key, "rvw-" + i, 0, 1)));
            out.add(new SupplierReview(author, when, stars, text));
        }
        return out;
    }

    // -- One sentence ---------------------------------------------------------------------------

    private static final Map<String, String> ADVICE = Map.of(
            "delivery", "use for non-urgent orders only",
            "quality", "inspect every lot",
            "communication", "confirm every order in writing",
            "pricing", "negotiate before every order");

    /** What to do about this supplier, in one plain sentence. {@code preview} is the lookup card, before they are on the panel. */
    static String recommendation(double rating, int reviewCount, RatingBreakdown breakdown, double priceIndex,
            boolean preview) {
        String label = ratingLabel(rating);
        Map<String, Double> entries = new LinkedHashMap<>();
        for (String k : RatingBreakdown.KEYS) {
            entries.put(k, breakdown.get(k));
        }
        String strongest = entries.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .findFirst().orElseThrow().getKey();
        String weakest = entries.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .findFirst().orElseThrow().getKey();
        String stars = Js.toFixed(rating, 1) + " ★";
        String from = "from " + Js.localeInt(reviewCount) + " buyers";
        String price = priceIndex < 99.5
                ? "priced " + Math.round(100 - priceIndex) + "% under market"
                : priceIndex > 100.5
                        ? "priced " + Math.round(priceIndex - 100) + "% over market"
                        : "priced at market";
        if ("Excellent".equals(label)) {
            return (preview ? "Add to the panel" : "Core supplier") + ": " + stars + " " + from + ", strong on "
                    + strongest + ", " + price + ".";
        }
        if ("Good".equals(label)) {
            return (preview ? "Add to the panel" : "Reliable choice") + ": " + stars + " " + from + ", " + price
                    + "; " + weakest + " is the only soft spot.";
        }
        if ("Fair".equals(label)) {
            return (preview ? "Add as a backup only" : "Use with care") + ": " + stars + ", " + weakest
                    + " is the weak point; fine for standard orders, not urgent ones.";
        }
        return "Do not rely on alone: " + stars + ", " + weakest + " is the weak point; " + ADVICE.get(weakest)
                + ".";
    }

    /**
     * Six months of on-time record around a supplier's own OTIF, for a supplier added
     * outside the seeded panel. A port of {@code otifTrendFor} in the frontend's
     * {@code platform/data.ts}: a supplier that misses dates does not suddenly become
     * reliable last month, so the trend is a walk around their own OTIF rather than six
     * unrelated numbers.
     */
    static List<Double> otifTrendFor(String key, double otifPct) {
        List<Double> out = new ArrayList<>(6);
        for (int m = 0; m < 6; m++) {
            double v = Math.min(99.5, Math.max(62, otifPct + Seeded.randRange(key, "trend" + m, -6, 6)));
            out.add(Js.round2(v));
        }
        return out;
    }

    // -- The panel in numbers ---------------------------------------------------------------------

    /** {@code otifPct} is null for a supplier whose on-time rate was never provided. */
    record PanelRow(boolean custom, double rating, double spendShare12m, Double otifPct) {
    }

    static PanelSummary panelSummary(List<PanelRow> rows) {
        int n = rows.isEmpty() ? 1 : rows.size();
        double spend = rows.stream().mapToDouble(PanelRow::spendShare12m).sum();
        if (spend == 0) {
            spend = 1;
        }
        double spendRated4 = rows.stream().filter(r -> r.rating() >= 4).mapToDouble(PanelRow::spendShare12m).sum();
        // The average on-time rate is over the suppliers that have one; the rest are not zero.
        List<PanelRow> withOtif = rows.stream().filter(r -> r.otifPct() != null).toList();
        double avgOtif = withOtif.isEmpty() ? 0
                : withOtif.stream().mapToDouble(PanelRow::otifPct).sum() / withOtif.size();
        return new PanelSummary(
                rows.size(),
                (int) rows.stream().filter(PanelRow::custom).count(),
                round1(rows.stream().mapToDouble(PanelRow::rating).sum() / n),
                (int) Math.round(spendRated4 / spend * 100),
                (int) rows.stream().filter(r -> "Weak".equals(ratingLabel(r.rating()))).count(),
                round1(avgOtif));
    }
}
