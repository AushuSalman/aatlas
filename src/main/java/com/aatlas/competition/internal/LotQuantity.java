package com.aatlas.competition.internal;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many units a listing's title says it holds: "Lot of 50", "Case of 25", "10-Pack",
 * "100 pcs", "25/case", "Box of 100". Null when the title names no quantity - a single unit,
 * or a pack whose size cannot be read, which is then not a per-unit price anybody should use.
 *
 * <p>Dimensions are deliberately not read: "1/2 in x 10 ft" is a size, not a count. Pure.
 */
final class LotQuantity {

    static final int MIN = 2;
    static final int MAX = 10_000;

    private static final String CONTAINER = "lot|case|pack|box|bag|set|bundle|carton|pail|bucket|qty|quantity|pkg|package";

    private static final List<Pattern> PATTERNS = List.of(
            // "lot of 50", "case of 25", "pack: 10", "qty 20"
            Pattern.compile("\\b(?:" + CONTAINER + ")\\s*(?:of|:)?\\s*\\(?(\\d{1,5})\\)?\\b", Pattern.CASE_INSENSITIVE),
            // "10-pack", "50 pcs", "100ct", "12 count", "25 pieces", "6 pk"
            Pattern.compile("\\b(\\d{1,5})\\s*-?\\s*(?:pack|pk|pks|pcs|pc|pieces|piece|count|ct|units|lot)\\b",
                    Pattern.CASE_INSENSITIVE),
            // "25/case", "100 / box", "10 per pack"
            Pattern.compile("\\b(\\d{1,5})\\s*(?:/|per)\\s*(?:" + CONTAINER + ")\\b", Pattern.CASE_INSENSITIVE));

    private LotQuantity() {
    }

    static Integer parse(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(title);
            while (m.find()) {
                int n = Integer.parseInt(m.group(1));
                if (n >= MIN && n <= MAX) {
                    return n;
                }
            }
        }
        return null;
    }
}
