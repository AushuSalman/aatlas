package com.aatlas.suppliers.internal;

import java.util.regex.Pattern;

/**
 * "Pull their information from the web" - honestly. The platform does not fetch anything: no
 * search, no registry lookup, no web crawl. Earlier this endpoint synthesised an entire
 * company profile (city, years trading, certifications, review text, "sources" it claimed to
 * have checked) deterministically from a hash of the query, so the same fake company came
 * back every time and nothing anywhere said it was invented. That was a real risk in front of
 * a prospect and is gone.
 *
 * <p>What is left is what it should always have been: parse whatever the buyer typed into a
 * name and, if it looks like a URL, a cleaner display form of it; report that nothing was
 * found; hand back only the country they told us, never a guess. The buyer fills in the rest
 * by hand, or imports a CSV.
 */
final class LookupScoring {

    private LookupScoring() {
    }

    private static final Pattern URL_LIKE =
            Pattern.compile("^(https?://)?(www\\.)?[a-z0-9-]+(\\.[a-z]{2,})+(/.*)?$", Pattern.CASE_INSENSITIVE);

    /** What the honest lookup found: nothing. Just the buyer's own query, tidied. */
    record Outcome(String query, String country, Draft draft) {
    }

    /** The one thing this endpoint can honestly offer: a name (cleaned up if it was a URL) and the country the buyer typed. */
    record Draft(String name, String country) {
    }

    static Outcome lookup(String query, String country) {
        String raw = query.strip().replaceAll("\\s+", " ");
        boolean looksLikeUrl = URL_LIKE.matcher(raw).matches();
        String name = raw;
        if (looksLikeUrl) {
            String host = raw.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^www\\.", "")
                    .replaceFirst("/.*$", "");
            // A URL is not a company name; showing the domain's own words back is tidying up
            // what was typed, not inventing a fact about the world.
            name = titleCase(host.split("\\.")[0].replaceAll("[-_]+", " "));
        }
        String cleanCountry = country == null || country.isBlank() ? null : country.strip();
        return new Outcome(raw, cleanCountry, new Draft(name, cleanCountry));
    }

    private static String titleCase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean atBoundary = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isWord = Character.isLetterOrDigit(c) || c == '_';
            out.append(isWord && atBoundary ? Character.toUpperCase(c) : c);
            atBoundary = !isWord;
        }
        return out.toString();
    }
}
