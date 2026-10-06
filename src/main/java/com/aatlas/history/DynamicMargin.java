package com.aatlas.history;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The margin to aim for, learned from what the tenant really earns rather than one fixed
 * percentage - and the floor no price should go under.
 *
 * <p>Built from the bottom up, each level trusted as far as its sales allow:
 * <ol>
 * <li>the category's realised margin (lines-weighted median of its items), shrunk toward the
 *     industry benchmark for the category while it has few sales;</li>
 * <li>moved for the item's price level - pricier items carry a lower percentage - by a slope
 *     learned across the tenant's own catalogue (points of margin per doubling of price);</li>
 * <li>the item's own realised margin, trusted by its own line count;</li>
 * <li>for an item with little history of its own, started below that and worked up as prices
 *     are applied for it.</li>
 * </ol>
 * The floor is the low end (10th percentile) of the margins actually sold at, the item's own
 * when it has enough lines, else its category's, never below cost and never above the target.
 * Pure: nothing here reads a row or a clock.
 */
public final class DynamicMargin {

    private DynamicMargin() {
    }

    /** Lines it takes for a category's own margin to count as much as the benchmark. */
    static final double CATEGORY_TRUST_LINES = 30;
    /** Lines it takes for an item's own margin to count as much as its category's. */
    static final double ITEM_TRUST_LINES = 12;
    /** Fewer own lines than this and an item is "new": it starts below its category and works up. */
    static final long NEW_ITEM_LINES = 8;
    /** Items needed before the price-level slope is learned rather than assumed. */
    static final int SLOPE_MIN_ITEMS = 8;
    /** Assumed until learned: two points less margin each time the price doubles. */
    static final double DEFAULT_SLOPE = -2;
    static final double SLOPE_MIN = -8;
    static final double SLOPE_MAX = 2;
    /** The benchmark used when the category has none at all. */
    static final double FALLBACK_TARGET = 30;
    static final double FALLBACK_LOW = 22;
    /** "No value" for a quantile: NaN, tested with {@link #none}. */
    static final double NONE = 0.0 / 0.0;

    /**
     * The learned margin for one item.
     *
     * @param targetPct the gross margin to aim for, % of price
     * @param floorPct the lowest margin a price should carry, % of price (0 = at cost)
     * @param basis one line for a tile: where the target came from
     * @param note the derivation's full sentence
     * @param floorNote where the floor came from
     */
    public record Target(BigDecimal targetPct, BigDecimal floorPct, String basis, String note, String floorNote) {
    }

    /**
     * @param category the item's category, null when uncategorised
     * @param priceRef what the item sells for (today's price, else its last), to place it on the price-level curve
     * @param benchmark the industry benchmark for the category, null when none
     * @param priorApplied prices already applied for the item at this branch: how far up the entry ramp it is
     * @param priceLevelOn whether the price-level slope applies
     * @param entryShare where a new item starts, as a share of its usual margin (0.5-1)
     * @param rampDecisions decisions it takes to reach the full margin
     */
    public static Target learn(MarginProfiles.Profile profile, UUID productId, String category, BigDecimal priceRef,
            Reference.Benchmark benchmark, long priorApplied, boolean priceLevelOn, double entryShare,
            double rampDecisions) {
        List<MarginProfiles.ItemMargin> items = profile == null ? List.of() : profile.items();
        double priorTarget = benchmark != null && benchmark.targetMarginPct() != null
                ? benchmark.targetMarginPct().doubleValue() : FALLBACK_TARGET;
        double priorLow = benchmark != null && benchmark.lowMarginPct() != null
                ? benchmark.lowMarginPct().doubleValue() : FALLBACK_LOW;
        String catName = category == null || category.isBlank() ? "this category" : category;

        List<MarginProfiles.ItemMargin> inCategory = new ArrayList<>();
        MarginProfiles.ItemMargin own = null;
        for (MarginProfiles.ItemMargin m : items) {
            if (m.marginPct() == null) {
                continue;
            }
            if (m.productId().equals(productId)) {
                own = m;
            }
            if (sameCategory(m.category(), category)) {
                inCategory.add(m);
            }
        }

        // 1. the category, shrunk toward the benchmark while thin
        long catLines = inCategory.stream().mapToLong(MarginProfiles.ItemMargin::lines).sum();
        double catMargin = weightedQuantile(inCategory, 0.5, false);
        double catTarget = none(catMargin) ? priorTarget
                : (catLines * catMargin + CATEGORY_TRUST_LINES * priorTarget) / (catLines + CATEGORY_TRUST_LINES);

        StringBuilder note = new StringBuilder();
        String basis;
        if (none(catMargin)) {
            note.append("No sales with a cost in ").append(catName).append(" yet, so the industry benchmark ")
                    .append(fmt(priorTarget)).append("% is the start");
            basis = "industry benchmark for " + catName + " (no sales yet)";
        } else {
            note.append(capitalize(catName)).append(" earns ").append(fmt(catMargin)).append("% over ")
                    .append(catLines).append(" sale").append(catLines == 1 ? "" : "s");
            if (catLines < CATEGORY_TRUST_LINES * 3) {
                note.append(" (weighed against the ").append(fmt(priorTarget)).append("% benchmark → ")
                        .append(fmt(catTarget)).append("%)");
            }
            basis = "learned from " + catLines + " sale" + (catLines == 1 ? "" : "s") + " in " + catName;
        }

        // 2. the item's price level
        double level = catTarget;
        if (priceLevelOn) {
            double catPrice = weightedQuantile(inCategory, 0.5, true);
            double slope = slope(items);
            if (!none(catPrice) && catPrice > 0 && priceRef != null && priceRef.signum() > 0) {
                double doublings = clamp(log2(priceRef.doubleValue() / catPrice), -3, 3);
                double shift = clamp(slope * doublings, -10, 10);
                if (Math.abs(shift) >= 0.1) {
                    level = catTarget + shift;
                    note.append("; ").append(shift < 0 ? "−" : "+").append(fmt(Math.abs(shift))).append(" pts as a ")
                            .append(shift < 0 ? "pricier" : "cheaper").append(" item than the category's typical $")
                            .append(fmt2(catPrice));
                }
            }
        }

        // 3. the item's own margin
        double target = level;
        long ownLines = own == null ? 0 : own.lines();
        if (own != null) {
            double ownMargin = own.marginPct().doubleValue();
            target = (ownLines * ownMargin + ITEM_TRUST_LINES * level) / (ownLines + ITEM_TRUST_LINES);
            note.append("; this item earns ").append(fmt(ownMargin)).append("% over ").append(ownLines)
                    .append(" sale").append(ownLines == 1 ? "" : "s");
        }

        // 4. a new item starts below and works up
        if (ownLines < NEW_ITEM_LINES && entryShare < 1) {
            double progress = rampDecisions <= 0 ? 1 : Math.min(1, priorApplied / rampDecisions);
            double share = entryShare + (1 - entryShare) * progress;
            if (share < 0.999) {
                target *= share;
                note.append("; a new item, so it starts at ").append(fmt(share * 100)).append("% of that (")
                        .append(priorApplied).append(" of ").append(fmt(rampDecisions)).append(" decisions in)");
            }
        }

        // the floor: the low end of what is really sold at
        double floor;
        String floorNote;
        if (own != null && own.lowMarginPct() != null && ownLines >= 20) {
            floor = own.lowMarginPct().doubleValue();
            floorNote = "the low end of this item's own margins (" + ownLines + " sales)";
        } else {
            double catLow = weightedQuantile(inCategory, 0.1, false);
            if (!none(catLow)) {
                floor = (catLines * catLow + 10 * priorLow) / (catLines + 10.0);
                floorNote = "the low end of " + catName + "'s margins (" + catLines + " sales)";
            } else {
                floor = priorLow;
                floorNote = "the low end of the industry band for " + catName + " (no sales yet)";
            }
        }
        target = clamp(target, 0.5, 85);
        floor = clamp(floor, 0, target * 0.85);
        note.append(" → target ").append(fmt(target)).append("%.");
        return new Target(pct(target), pct(floor), basis, note.toString(),
                "Floor " + fmt(floor) + "% margin: " + floorNote + ", never below cost.");
    }

    /**
     * Points of margin per doubling of price, learned within categories across the catalogue: each
     * item's margin and price against its category's median, weighted by its lines. Assumed
     * ({@value #DEFAULT_SLOPE}) until enough items are priced apart.
     */
    static double slope(List<MarginProfiles.ItemMargin> items) {
        java.util.Map<String, List<MarginProfiles.ItemMargin>> byCategory = new java.util.HashMap<>();
        for (MarginProfiles.ItemMargin m : items) {
            if (m.marginPct() != null && m.avgPrice() != null && m.avgPrice().signum() > 0 && m.lines() >= 3) {
                byCategory.computeIfAbsent(key(m.category()), k -> new ArrayList<>()).add(m);
            }
        }
        double sxy = 0;
        double sxx = 0;
        int n = 0;
        for (List<MarginProfiles.ItemMargin> group : byCategory.values()) {
            if (group.size() < 2) {
                continue;
            }
            double medPrice = weightedQuantile(group, 0.5, true);
            double medMargin = weightedQuantile(group, 0.5, false);
            if (none(medPrice) || none(medMargin) || medPrice <= 0) {
                continue;
            }
            for (MarginProfiles.ItemMargin m : group) {
                double w = Math.min(50, m.lines());
                double x = log2(m.avgPrice().doubleValue() / medPrice);
                double y = m.marginPct().doubleValue() - medMargin;
                sxy += w * x * y;
                sxx += w * x * x;
                n++;
            }
        }
        if (n < SLOPE_MIN_ITEMS || sxx < 1) {
            return DEFAULT_SLOPE;
        }
        return clamp(sxy / sxx, SLOPE_MIN, SLOPE_MAX);
    }

    /** The lines-weighted quantile of the items' margins ({@code price=false}) or prices; NaN with none. */
    static double weightedQuantile(List<MarginProfiles.ItemMargin> items, double q, boolean price) {
        List<MarginProfiles.ItemMargin> rows = items.stream()
                .filter(m -> (price ? m.avgPrice() : m.marginPct()) != null && m.lines() > 0)
                .sorted(Comparator.comparing(m -> price ? m.avgPrice() : m.marginPct())).toList();
        long total = rows.stream().mapToLong(MarginProfiles.ItemMargin::lines).sum();
        if (total == 0) {
            return NONE;
        }
        double cut = q * total;
        long seen = 0;
        for (MarginProfiles.ItemMargin m : rows) {
            seen += m.lines();
            if (seen >= cut) {
                return (price ? m.avgPrice() : m.marginPct()).doubleValue();
            }
        }
        return (price ? rows.get(rows.size() - 1).avgPrice() : rows.get(rows.size() - 1).marginPct()).doubleValue();
    }

    private static boolean sameCategory(String a, String b) {
        return key(a).equals(key(b));
    }

    private static String key(String category) {
        return category == null ? "" : category.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean none(double v) {
        return v != v;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static BigDecimal pct(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String fmt2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
