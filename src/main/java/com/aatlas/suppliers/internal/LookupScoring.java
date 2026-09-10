package com.aatlas.suppliers.internal;

import static com.aatlas.suppliers.internal.Js.clamp;
import static com.aatlas.suppliers.internal.Js.round1;
import static com.aatlas.suppliers.internal.SupplierScoring.certsFor;
import static com.aatlas.suppliers.internal.SupplierScoring.deliveryStars;
import static com.aatlas.suppliers.internal.SupplierScoring.otifFromStars;
import static com.aatlas.suppliers.internal.SupplierScoring.overall;
import static com.aatlas.suppliers.internal.SupplierScoring.pricingStars;
import static com.aatlas.suppliers.internal.SupplierScoring.qualityStars;
import static com.aatlas.suppliers.internal.SupplierScoring.reviewsFor;
import static com.aatlas.suppliers.internal.SupplierScoring.seededDefect;
import static com.aatlas.suppliers.internal.SupplierScoring.speedStars;

import com.aatlas.common.seed.Seeded;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * "Pull their information from the web." A port of {@code lookupSupplier} in the frontend's
 * {@code intel/suppliers.ts}. Nothing is fetched: the profile, its sources, reviews and
 * watch-outs are synthesised deterministically from the name (and the country, when it can
 * be inferred), so the same lookup always returns the same company, forever.
 */
final class LookupScoring {

    private LookupScoring() {
    }

    /** 2026: the platform's frozen "now" ({@code FIXTURE_NOW} in the frontend's {@code mock/pricing.ts}). */
    private static final int FIXTURE_YEAR = 2026;

    static final List<String> LOOKUP_COUNTRIES =
            List.of("USA", "Mexico", "Germany", "China", "India", "Vietnam", "UK", "Canada");

    private static final List<Map.Entry<String, Double>> COUNTRY_WEIGHTS = List.of(
            Map.entry("USA", 0.34),
            Map.entry("Mexico", 0.1),
            Map.entry("Germany", 0.12),
            Map.entry("China", 0.13),
            Map.entry("India", 0.08),
            Map.entry("Vietnam", 0.06),
            Map.entry("UK", 0.09),
            Map.entry("Canada", 0.08));

    private static final List<Map.Entry<String, String>> TLD = List.of(
            Map.entry(".co.uk", "UK"),
            Map.entry(".uk", "UK"),
            Map.entry(".de", "Germany"),
            Map.entry(".mx", "Mexico"),
            Map.entry(".cn", "China"),
            Map.entry(".in", "India"),
            Map.entry(".vn", "Vietnam"),
            Map.entry(".ca", "Canada"),
            Map.entry(".us", "USA"));

    private static final List<Map.Entry<Pattern, String>> COUNTRY_WORDS = List.of(
            Map.entry(Pattern.compile("\\b(germany|german|deutschland|gmbh|ag)\\b"), "Germany"),
            Map.entry(Pattern.compile("\\b(uk|united kingdom|britain|british|england|scotland|wales|plc|ltd)\\b"),
                    "UK"),
            Map.entry(Pattern.compile("\\b(mexico|mexican|s\\.?a\\.? de c\\.?v\\.?)\\b"), "Mexico"),
            Map.entry(Pattern.compile("\\b(china|chinese|shenzhen|shanghai|guangdong|ningbo)\\b"), "China"),
            Map.entry(Pattern.compile("\\b(india|indian|pvt|mumbai|pune|chennai)\\b"), "India"),
            Map.entry(Pattern.compile("\\b(vietnam|viet nam|hanoi|ho chi minh)\\b"), "Vietnam"),
            Map.entry(Pattern.compile("\\b(canada|canadian|ontario|quebec)\\b"), "Canada"),
            Map.entry(Pattern.compile("\\b(usa|u\\.s\\.|united states|america|american|inc|llc|corp|texas|ohio)\\b"),
                    "USA"));

    private static final Map<String, List<String>> CITIES = Map.of(
            "USA", List.of("Houston, TX", "Cleveland, OH", "Charlotte, NC", "Phoenix, AZ", "Chicago, IL",
                    "Dallas, TX", "Pittsburgh, PA"),
            "Mexico", List.of("Monterrey", "Querétaro", "Guadalajara", "Tijuana"),
            "Germany", List.of("Düsseldorf", "Stuttgart", "Hamburg", "Nuremberg", "Essen"),
            "China", List.of("Ningbo", "Foshan", "Wenzhou", "Suzhou", "Tianjin"),
            "India", List.of("Pune", "Ahmedabad", "Coimbatore", "Rajkot"),
            "Vietnam", List.of("Hai Phong", "Binh Duong", "Dong Nai"),
            "UK", List.of("Sheffield", "Birmingham", "Leeds", "Glasgow"),
            "Canada", List.of("Mississauga, ON", "Calgary, AB", "Montréal, QC"));

    private static final Map<String, String> TLD_FOR = Map.of(
            "USA", "com", "Mexico", "mx", "Germany", "de", "China", "cn", "India", "in", "Vietnam", "vn",
            "UK", "co.uk", "Canada", "ca");

    /** Order to dock from the supplier's own gate; the lane adds transit on top. */
    private static final Map<String, int[]> LEAD = Map.of(
            "USA", new int[] {3, 12},
            "Canada", new int[] {4, 12},
            "Mexico", new int[] {5, 14},
            "Germany", new int[] {10, 26},
            "UK", new int[] {10, 24},
            "China", new int[] {18, 42},
            "India", new int[] {20, 45},
            "Vietnam", new int[] {18, 40});

    private static final List<Map.Entry<Pattern, String>> CATEGORY_WORDS = List.of(
            Map.entry(Pattern.compile("copper|brass|bronze|tube"), "Copper & brass"),
            Map.entry(Pattern.compile("valve|actuat"), "Valves"),
            Map.entry(Pattern.compile("polymer|plastic|pex|pvc|cpvc|resin"), "Polymers"),
            Map.entry(Pattern.compile("steel|metal|pipe|iron"), "Steel"),
            Map.entry(Pattern.compile("tool|machin|die |dies|cutting"), "Tooling"),
            Map.entry(Pattern.compile("fitting|coupling|flange|connector"), "Fittings"));

    private static final List<String> SUPPLIER_CATEGORIES =
            List.of("Copper & brass", "Valves", "Polymers", "Steel", "Tooling", "Fittings");

    private record Registry(String source, BiFunction<String, Integer, String> detail) {
    }

    private static final Map<String, Registry> REGISTRY = Map.of(
            "UK", new Registry("Companies House", (c, y) -> "Active · incorporated " + y),
            "USA", new Registry("SAM.gov", (c, y) -> "Registered · CAGE code on file"),
            "Germany", new Registry("Handelsregister", (c, y) -> "HRB entry · Amtsgericht " + c),
            "Mexico", new Registry("SAT (RFC)", (c, y) -> "RFC on file · active taxpayer"),
            "China", new Registry("NECIPS", (c, y) -> "Business licence verified"),
            "India", new Registry("MCA", (c, y) -> "CIN on file · active since " + y),
            "Vietnam", new Registry("National Business Registration Portal", (c, y) -> "Enterprise code on file"),
            "Canada", new Registry("Corporations Canada", (c, y) -> "Active · incorporated " + y));

    private static final Map<String, String> WATCH = Map.of(
            "delivery", "Several reviews mention late deliveries in the last two quarters.",
            "quality", "Two reviews report lots rejected on inspection; ask for a certificate of analysis with the first order.",
            "communication", "Slow to answer quotes and paperwork, according to recent reviews.",
            "pricing", "Priced above the market index; treat the first quote as an opening position.");

    record Outcome(String query, SupplierProfileView profile, List<LookupSource> sources, List<String> watchOuts,
            String recommendation) {
    }

    private static final Pattern URL_LIKE =
            Pattern.compile("^(https?://)?(www\\.)?[a-z0-9-]+(\\.[a-z]{2,})+(/.*)?$", Pattern.CASE_INSENSITIVE);

    static Outcome lookup(String query, String country) {
        String raw = query.strip().replaceAll("\\s+", " ");
        String q = raw.toLowerCase(Locale.ROOT);
        boolean looksLikeUrl = URL_LIKE.matcher(raw).matches();
        String host = looksLikeUrl
                ? raw.replaceFirst("(?i)^https?://", "").replaceFirst("(?i)^www\\.", "")
                        .replaceFirst("/.*$", "").toLowerCase(Locale.ROOT)
                : "";

        String ctry = country != null && LOOKUP_COUNTRIES.contains(country) ? country : null;
        if (ctry == null && !host.isEmpty()) {
            for (Map.Entry<String, String> e : TLD) {
                if (host.endsWith(e.getKey())) {
                    ctry = e.getValue();
                    break;
                }
            }
        }
        if (ctry == null) {
            for (Map.Entry<Pattern, String> e : COUNTRY_WORDS) {
                if (e.getKey().matcher(q).find()) {
                    ctry = e.getValue();
                    break;
                }
            }
        }
        if (ctry == null) {
            ctry = weightedCountry(q);
        }

        String seed = q + "|" + ctry;
        String name = looksLikeUrl
                ? titleCase(host.split("\\.")[0].replaceAll("[-_]+", " "))
                : raw;
        String website = !host.isEmpty() ? host : slug(name) + "." + TLD_FOR.get(ctry);
        String category = CATEGORY_WORDS.stream()
                .filter(e -> e.getKey().matcher(q).find())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseGet(() -> Seeded.pick(seed, "cat", SUPPLIER_CATEGORIES));
        String id = "cus-" + Long.toString(Seeded.hashString(seed), 36);
        int[] lead = LEAD.get(ctry);
        int leadTimeDays = Seeded.randInt(seed, "lead", lead[0], lead[1]);

        double defectPct = seededDefect(id);
        double quality = qualityStars(defectPct);
        double target = Seeded.randRange(seed, "rating", 2.8, 4.9);
        double rest = (target - SupplierScoring.WEIGHTS.get("quality") * quality)
                / (1 - SupplierScoring.WEIGHTS.get("quality"));
        double wantDelivery = clamp(rest + jitter(seed, "d"), 1, 5);
        double otifPct =
                otifFromStars(clamp((wantDelivery - 0.45 * speedStars(id, ctry, leadTimeDays)) / 0.55, 1, 5));
        double priceIndex = SupplierScoring.indexFromStars(clamp(round1(rest + jitter(seed, "p")), 3, 5));
        RatingBreakdown breakdown = new RatingBreakdown(
                quality,
                deliveryStars(id, ctry, leadTimeDays, otifPct),
                clamp(round1(rest + jitter(seed, "c")), 1, 5),
                pricingStars(priceIndex));
        double rating = overall(breakdown);
        int reviewCount = Seeded.randInt(seed, "reviews", 12, 640);
        int yearsTrading = Seeded.randInt(seed, "years", 3, 60);
        String city = Seeded.pick(seed, "city", CITIES.get(ctry));
        String employees = Seeded.pick(seed, "emp", List.of("11–50", "51–200", "201–500", "501–1,000"));
        int year = FIXTURE_YEAR - yearsTrading;
        List<String> certifications = certsFor(seed, category);

        Registry registry = REGISTRY.get(ctry);
        List<LookupSource> sources = new ArrayList<>();
        sources.add(rr(seed, "g") > 0.08
                ? new LookupSource("Google Business Profile", true,
                        Js.toFixed(rating, 1) + " ★ · " + reviewCount + " reviews")
                : new LookupSource("Google Business Profile", false, "No listing found"));
        double thomasThreshold = "USA".equals(ctry) || "Canada".equals(ctry) ? 0.2 : 0.6;
        sources.add(rr(seed, "t") > thomasThreshold
                ? new LookupSource("Thomasnet", true,
                        "Listed · " + category + " · " + certifications.size() + " certifications")
                : new LookupSource("Thomasnet", false, "Not listed"));
        sources.add(rr(seed, "d") > 0.12
                ? new LookupSource("Dun & Bradstreet", true,
                        "D-U-N-S on file · " + yearsTrading + " years trading · " + employees + " employees")
                : new LookupSource("Dun & Bradstreet", false, "No record"));
        sources.add(rr(seed, "l") > 0.15
                ? new LookupSource("LinkedIn", true, employees + " employees · " + city)
                : new LookupSource("LinkedIn", false, "No company page"));
        double registryThreshold = "USA".equals(ctry) ? 0.5 : 0.1;
        sources.add(rr(seed, "r") > registryThreshold
                ? new LookupSource(registry.source(), true, registry.detail().apply(city, year))
                : new LookupSource(registry.source(), false,
                        "USA".equals(ctry) ? "Not registered as a federal vendor" : "No entry found"));

        String ratingSource = sources.stream()
                .filter(LookupSource::found)
                .limit(3)
                .map(LookupSource::source)
                .reduce((a, b) -> a + " · " + b)
                .orElse("Web search");

        List<String> watchOuts = new ArrayList<>();
        if (rating < 3.6) {
            List<Map.Entry<String, Double>> weak = new ArrayList<>();
            for (String k : RatingBreakdown.KEYS) {
                weak.add(Map.entry(k, breakdown.get(k)));
            }
            weak.sort(java.util.Comparator.comparingDouble(Map.Entry::getValue));
            for (Map.Entry<String, Double> e : weak) {
                if (e.getValue() < 3.4 && watchOuts.size() < 2) {
                    watchOuts.add(WATCH.get(e.getKey()));
                }
            }
            if (watchOuts.isEmpty()) {
                watchOuts.add(WATCH.get(weak.get(0).getKey()));
            }
        }

        SupplierProfileView profile = new SupplierProfileView(
                id, name, ctry, city, website, category, yearsTrading, certifications, rating, reviewCount,
                ratingSource, breakdown, reviewsFor(seed, rating), 0, leadTimeDays, otifPct, priceIndex,
                defectPct, SupplierScoring.holdsStock(id), true, null, sources, watchOuts, null, null);

        String recommendation = SupplierScoring.recommendation(rating, reviewCount, breakdown, priceIndex, true);
        return new Outcome(raw, profile, sources, watchOuts, recommendation);
    }

    private static double jitter(String seed, String salt) {
        return Seeded.randRange(seed, salt, -0.5, 0.5);
    }

    private static double rr(String seed, String salt) {
        return Seeded.rand(seed, "src-" + salt);
    }

    private static String weightedCountry(String seed) {
        double x = Seeded.rand(seed, "country");
        for (Map.Entry<String, Double> e : COUNTRY_WEIGHTS) {
            x -= e.getValue();
            if (x <= 0) {
                return e.getKey();
            }
        }
        return "USA";
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

    private static String slug(String s) {
        String out = s.toLowerCase(Locale.ROOT).replace("&", "and").replaceAll("[^a-z0-9]+", "");
        if (out.length() > 24) {
            out = out.substring(0, 24);
        }
        return out.isEmpty() ? "supplier" : out;
    }
}
