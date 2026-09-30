package com.aatlas.competition.internal;

import com.aatlas.competition.internal.ShoppingProvider.Listing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which listings a keyword search returned are this item's price, and why the rest are not.
 *
 * <p>A shopping search for "1/2 in PVC ball valve" also returns a 2-inch valve, a 10-pack,
 * a wrench, and the same valve from the same store three times. Five rules, in order, each
 * naming what it dropped so the screen can show it:
 * <ol>
 *   <li>no price, or a price in another currency;</li>
 *   <li>a title sharing too few of the query's words ({@link #MIN_MATCH});</li>
 *   <li>a different size: the query says 1/2 in and the title only 1-1/4 in;</li>
 *   <li>a listing sold as a lot, case or pack - its price is not one unit's;</li>
 *   <li>a second listing from a seller already kept - one observation per competitor per day,
 *       which is what {@code competitor_prices} holds and what the median should count;</li>
 *   <li>with three or more left, a price under a third or over three times their median: a
 *       pack, a part, or a different size.</li>
 * </ol>
 * Pure: no I/O, no clock.
 */
final class ListingFilter {

    /** Share of the query's words a title must contain. */
    static final BigDecimal MIN_MATCH = new BigDecimal("0.34");
    static final BigDecimal OUTLIER_FACTOR = BigDecimal.valueOf(3);

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "from", "per", "each", "inch",
            "pack", "new", "set", "pcs", "piece");

    private ListingFilter() {
    }

    record Judged(Listing listing, boolean kept, String reason, BigDecimal match) {
    }

    static List<Judged> judge(String query, String currency, List<Listing> listings) {
        return judge(query, currency, listings, true);
    }

    /**
     * @param singleUnits a single unit's price is wanted (competitor and retail prices): drop listings
     *                    sold as a lot, case or pack, and keep one listing per seller. Off for bulk lots
     *                    on the buy side, which are divided down to a unit price first and where several
     *                    lots from eBay are several data points.
     */
    static List<Judged> judge(String query, String currency, List<Listing> listings, boolean singleUnits) {
        Set<String> words = words(query);
        Set<BigDecimal> querySizes = sizes(query);
        List<Judged> out = new ArrayList<>();
        List<Integer> candidates = new ArrayList<>();
        Set<String> sellers = new HashSet<>();
        for (Listing l : listings) {
            BigDecimal match = match(words, l.title());
            if (l.price() == null || l.price().signum() <= 0) {
                out.add(new Judged(l, false, "No price on the listing", match));
            } else if (l.currency() != null && !l.currency().equalsIgnoreCase(currency)) {
                out.add(new Judged(l, false, "Priced in " + l.currency() + ", not " + currency, match));
            } else if (match.compareTo(MIN_MATCH) < 0) {
                out.add(new Judged(l, false, "Title matches too little of the item (" + pct(match) + " of its words)",
                        match));
            } else if (!sameSize(querySizes, l.title())) {
                out.add(new Judged(l, false, "A different size (" + String.join(", ", sizeLabels(l.title()))
                        + ", not " + String.join(" or ", sizeLabels(query)) + ")", match));
            } else if (singleUnits && multiUnit(l.title())) {
                out.add(new Judged(l, false, "Sold as a lot or pack, not a single unit", match));
            } else if (singleUnits && !sellers.add(seller(l))) {
                out.add(new Judged(l, false, "Another listing from " + l.merchant() + " was kept", match));
            } else {
                candidates.add(out.size());
                out.add(new Judged(l, true, null, match));
            }
        }
        if (candidates.size() >= 3) {
            BigDecimal median = median(candidates.stream().map(i -> out.get(i).listing().price()).toList());
            BigDecimal low = median.divide(OUTLIER_FACTOR, 4, RoundingMode.HALF_UP);
            BigDecimal high = median.multiply(OUTLIER_FACTOR);
            for (int i : candidates) {
                Judged j = out.get(i);
                BigDecimal p = j.listing().price();
                if (p.compareTo(low) < 0 || p.compareTo(high) > 0) {
                    String times = p.divide(median, 1, RoundingMode.HALF_UP).toPlainString();
                    out.set(i, new Judged(j.listing(), false,
                            "Far from the other listings (" + times + "× their median) - likely a pack, a part or another size",
                            j.match()));
                }
            }
        }
        return out;
    }

    /**
     * One key per store however a source spells it: "The Home Depot", "Home Depot" and
     * "homedepot.com" are one competitor, so a store's own page and its Google Shopping listing
     * count once.
     */
    static String seller(Listing l) {
        if (l.merchant() == null) {
            return "";
        }
        String s = l.merchant().strip().toLowerCase(Locale.ROOT);
        s = s.replaceFirst("^the\\s+", "").replaceFirst("^www\\.", "")
                .replaceFirst("\\.(com|co\\.uk|net|org|us|biz|store|shop)$", "");
        return s.replaceAll("[^a-z0-9]", "");
    }

    /**
     * A size in inches, the way plumbing and HVAC titles write it: a mixed fraction ("1-1/4"), a bare
     * fraction ("1/2", read as inches - the trade's habit), or a number with an inch mark
     * ("1.25 inch", "3/4 in.", "1\""). A bare whole number without a unit is not a size.
     */
    private static final java.util.regex.Pattern SIZE = java.util.regex.Pattern.compile(
            "(?<![\\d/.])(?:(\\d+)-(\\d+)/(\\d+)|(\\d+)/(\\d+)|(\\d+(?:\\.\\d+)?)(?=\\s*(?:in\\b|in\\.|inch|\"|″|'')))",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Every size in a text, in inches. */
    static Set<BigDecimal> sizes(String text) {
        Set<BigDecimal> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        java.util.regex.Matcher m = SIZE.matcher(text);
        while (m.find()) {
            BigDecimal v;
            if (m.group(1) != null) {
                v = new BigDecimal(m.group(1)).add(fraction(m.group(2), m.group(3)));
            } else if (m.group(4) != null) {
                v = fraction(m.group(4), m.group(5));
            } else {
                v = new BigDecimal(m.group(6));
            }
            if (v != null && v.signum() > 0 && v.compareTo(BigDecimal.valueOf(120)) <= 0) {
                out.add(v.setScale(3, RoundingMode.HALF_UP));
            }
        }
        return out;
    }

    private static BigDecimal fraction(String num, String den) {
        BigDecimal d = new BigDecimal(den);
        return d.signum() == 0 ? BigDecimal.ZERO : new BigDecimal(num).divide(d, 3, RoundingMode.HALF_UP);
    }

    /**
     * The same item size: when the query names a size and the title names sizes, one of them must
     * match. A title naming none is not judged - it may simply leave the size out.
     */
    static boolean sameSize(Set<BigDecimal> querySizes, String title) {
        if (querySizes.isEmpty()) {
            return true;
        }
        Set<BigDecimal> titleSizes = sizes(title);
        return titleSizes.isEmpty() || titleSizes.stream().anyMatch(querySizes::contains);
    }

    private static List<String> sizeLabels(String text) {
        return sizes(text).stream().map(v -> v.stripTrailingZeros().toPlainString() + " in").toList();
    }

    private static final java.util.regex.Pattern MULTI_WORDS = java.util.regex.Pattern.compile(
            "\\b(lot|lots|bulk|wholesale|case of|pack of|box of|bundle)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * A title selling more than one unit: a readable quantity ("10-pack", "case of 25"), or the words
     * a lot is sold under even without one ("Ball Valve Lot").
     */
    static boolean multiUnit(String title) {
        return title != null && (LotQuantity.parse(title) != null || MULTI_WORDS.matcher(title).find());
    }

    /** The query's meaningful words: three letters or more, or anything with a digit ("1/2", "3x4"). */
    static Set<String> words(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9/.]+")) {
            String t = w.replaceAll("^[/.]+|[/.]+$", "");
            if (t.isEmpty() || STOP.contains(t)) {
                continue;
            }
            if (t.length() >= 3 || t.chars().anyMatch(Character::isDigit)) {
                out.add(t);
            }
        }
        return out;
    }

    static BigDecimal match(Set<String> words, String title) {
        if (words.isEmpty()) {
            return BigDecimal.ONE;
        }
        Set<String> have = words(title);
        String flat = title == null ? "" : title.toLowerCase(Locale.ROOT);
        long hits = words.stream().filter(w -> have.contains(w) || flat.contains(w)).count();
        return BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(words.size()), 2, RoundingMode.HALF_UP);
    }

    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    private static String pct(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%";
    }
}
