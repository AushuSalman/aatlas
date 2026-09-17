package com.aatlas.suppliers.internal;

import static com.aatlas.suppliers.internal.Js.clamp;
import static com.aatlas.suppliers.internal.Js.round1;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stars, derived from what the platform already measures. A port of the star-scoring half of
 * the frontend's {@code intel/suppliers.ts}: the on-time record, the lead time and stock
 * position, and the price index - plus {@code communication}, the one dimension nothing in
 * the system measures, which is accepted as given rather than derived.
 *
 * <p>Every star function is null-safe: a supplier known only from a purchase order, or one
 * being edited with some fields still absent, has no defect rate, price index or on-time rate
 * on file for some dimensions, and the corresponding star is {@code null} rather than a
 * hashed guess. {@link #overall} takes the weighted mean over whichever dimensions are
 * present, with the remaining weights renormalised - never zero-filling an absent one.
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
     * Delivery speed, from the supplier's own gate plus the lane's inbound transit, floored
     * when they are known to hold stock (or rush). {@code holdsStock} is the file/observed
     * fact, never a hash of the supplier's id; {@code null} means unknown, which carries no
     * floor. Null when the lead time itself was never provided.
     */
    static Double speedStars(String country, Integer leadTimeDays, Boolean holdsStock) {
        if (leadTimeDays == null) {
            return null;
        }
        double own = leadStars(leadTimeDays + inboundDays(country));
        if (!Boolean.TRUE.equals(holdsStock)) {
            return own;
        }
        double stockFloor = "USA".equals(country) ? 5 : "Mexico".equals(country) ? 4.6 : 4.2;
        return Math.max(own, stockFloor);
    }

    /**
     * Delivery as a buyer experiences it: the on-time record, and how fast the goods can be
     * here. Null when neither the lead time nor the on-time rate is known; a weighted mean of
     * whichever of the two is present when only one is.
     */
    static Double deliveryStars(String country, Integer leadTimeDays, Double otifPct, Boolean holdsStock) {
        Double speed = speedStars(country, leadTimeDays, holdsStock);
        Double otif = otifPct == null ? null : otifStars(otifPct);
        if (speed == null && otif == null) {
            return null;
        }
        if (speed == null) {
            return round1(otif);
        }
        if (otif == null) {
            return round1(speed);
        }
        return round1(0.55 * otif + 0.45 * speed);
    }

    /** Price index to stars. A vetted panel sits between three and five: 89 is five, 114 is three. Null without a price index. */
    static Double pricingStars(Double idx) {
        return idx == null ? null : clamp(round1(5 - ((idx - 89) / 25) * 2), 3, 5);
    }

    static double indexFromStars(double stars) {
        return Js.round2(89 + ((5 - stars) / 2) * 25);
    }

    /** Defect rate to stars, on the same scale: 0.2% is five, 3.4% is three. Null without a defect rate. */
    static Double qualityStars(Double defect) {
        return defect == null ? null : clamp(round1(5 - ((defect - 0.2) / 3.2) * 2), 3, 5);
    }

    /** Delivery carries the most weight: for a distributor, "did it arrive when promised" is the review. */
    static final Map<String, Double> WEIGHTS = Map.of(
            "quality", 0.15, "delivery", 0.4, "communication", 0.3, "pricing", 0.15);

    /**
     * The weighted mean over whichever dimensions are present, with the remaining weights
     * renormalised so an absent dimension is skipped rather than scored as zero. Null when no
     * dimension is present at all - the caller writes no {@code supplier_ratings} row for it.
     */
    static Double overall(RatingBreakdown b) {
        double sum = 0;
        double weight = 0;
        for (String k : RatingBreakdown.KEYS) {
            Double v = b.get(k);
            if (v != null) {
                double w = WEIGHTS.get(k);
                sum += v * w;
                weight += w;
            }
        }
        return weight == 0 ? null : clamp(round1(sum / weight), 1, 5);
    }

    static String ratingLabel(Double rating) {
        if (rating == null) {
            return "Not assessed";
        }
        return rating >= 4.5 ? "Excellent" : rating >= 4 ? "Good" : rating >= 3.3 ? "Fair" : "Weak";
    }

    // -- One sentence ---------------------------------------------------------------------------

    private static final Map<String, String> ADVICE = Map.of(
            "delivery", "use for non-urgent orders only",
            "quality", "inspect every lot",
            "communication", "confirm every order in writing",
            "pricing", "negotiate before every order");

    /** What to do about this supplier, in one plain sentence. {@code preview} is the lookup card, before they are on the panel. */
    static String recommendation(double rating, int reviewCount, RatingBreakdown breakdown, Double priceIndex,
            boolean preview) {
        String label = ratingLabel(rating);
        Map<String, Double> entries = new LinkedHashMap<>();
        for (String k : RatingBreakdown.KEYS) {
            Double v = breakdown.get(k);
            if (v != null) {
                entries.put(k, v);
            }
        }
        if (entries.isEmpty()) {
            return "Not enough information to recommend this supplier yet.";
        }
        String strongest = entries.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed())
                .findFirst().orElseThrow().getKey();
        String weakest = entries.entrySet().stream()
                .sorted(Comparator.comparingDouble(Map.Entry::getValue))
                .findFirst().orElseThrow().getKey();
        String stars = Js.toFixed(rating, 1) + " ★";
        String from = "from " + Js.localeInt(reviewCount) + " buyers";
        String price = priceIndex == null
                ? "no price index on file"
                : priceIndex < 99.5
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

    // -- The panel in numbers ---------------------------------------------------------------------

    /** {@code rating}/{@code otifPct} are null for a supplier that has never been assessed on that dimension. */
    record PanelRow(boolean custom, Double rating, double spendShare12m, Double otifPct) {
    }

    static PanelSummary panelSummary(List<PanelRow> rows) {
        List<PanelRow> rated = rows.stream().filter(r -> r.rating() != null).toList();
        int n = rated.isEmpty() ? 1 : rated.size();
        double spend = rows.stream().mapToDouble(PanelRow::spendShare12m).sum();
        if (spend == 0) {
            spend = 1;
        }
        double spendRated4 = rows.stream().filter(r -> r.rating() != null && r.rating() >= 4)
                .mapToDouble(PanelRow::spendShare12m).sum();
        // The average on-time rate is over the suppliers that have one; the rest are not zero.
        List<PanelRow> withOtif = rows.stream().filter(r -> r.otifPct() != null).toList();
        double avgOtif = withOtif.isEmpty() ? 0
                : withOtif.stream().mapToDouble(PanelRow::otifPct).sum() / withOtif.size();
        return new PanelSummary(
                rows.size(),
                (int) rows.stream().filter(PanelRow::custom).count(),
                round1(rated.stream().mapToDouble(PanelRow::rating).sum() / n),
                (int) Math.round(spendRated4 / spend * 100),
                (int) rated.stream().filter(r -> "Weak".equals(ratingLabel(r.rating()))).count(),
                round1(avgOtif));
    }
}
