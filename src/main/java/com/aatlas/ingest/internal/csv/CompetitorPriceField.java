package com.aatlas.ingest.internal.csv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The columns of a competitor-price observation file: what you saw, where, and when. */
public enum CompetitorPriceField implements ImportFieldSpec {

    ITEM("item", "Item number", true,
            "Your internal SKU the observation is for. An unknown item is added to the catalogue.",
            "Item No", "HRD118902", ImportField.ITEM.synonyms()),

    COMPETITOR("competitor", "Competitor", true,
            "Who was selling it. One name per competitor, spelled consistently.",
            "Competitor", "Northline Supply",
            List.of("competitor", "competitorname", "rival", "seller", "retailer", "merchant", "shop",
                    "marketplace")),

    PRICE("price", "Price", true,
            "The price observed, per selling unit, above zero.",
            "Price", "28.40",
            List.of("price", "competitorprice", "theirprice", "marketprice", "observedprice", "listprice",
                    "sellprice", "unitprice", "shelfprice", "webprice", "onlineprice", "advertisedprice", "rrp",
                    "retailprice")),

    CURRENCY("currency", "Currency", false,
            "Must be your trading currency. Blank means it already is.",
            "Currency", "USD", ImportField.CURRENCY.synonyms()),

    REGION("region", "Region or branch", false,
            "A branch code, a state, or one of south/west/north/east. Anchors the observation to a market.",
            "Region/Branch", "south",
            List.of("region", "regionbranch", "branch", "store", "storeno", "location", "market", "area",
                    "territory", "regionkey", "whse", "warehouse", "site", "msa", "state")),

    OBSERVED_AT("observedAt", "Observed date", false,
            "When you saw the price. Blank means today; observations older than 180 days are not used.",
            "Observed Date", "2026-09-10",
            List.of("observeddate", "observed", "dateobserved", "checkedon", "asof", "asofdate", "capturedat",
                    "collected", "seen", "pricedate", "lastchecked", "scrapedat", "timestamp", "date")),

    SOURCE_URL("sourceUrl", "Source URL", false,
            "Where the price was seen, for the audit trail.",
            "Source URL", "",
            List.of("sourceurl", "url", "link", "source", "href", "website", "page", "producturl",
                    "listingurl"));

    private static final Map<String, CompetitorPriceField> BY_KEY = java.util.Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CompetitorPriceField::key, Function.identity()));

    private final String key;
    private final String label;
    private final boolean required;
    private final String hint;
    private final String header;
    private final String example;
    private final List<String> synonyms;

    CompetitorPriceField(String key, String label, boolean required, String hint, String header, String example,
            List<String> synonyms) {
        this.key = key;
        this.label = label;
        this.required = required;
        this.hint = hint;
        this.header = header;
        this.example = example;
        this.synonyms = synonyms;
    }

    @Override
    @JsonValue
    public String key() {
        return key;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public boolean required() {
        return required;
    }

    @Override
    public String hint() {
        return hint;
    }

    @Override
    public String header() {
        return header;
    }

    @Override
    public String example() {
        return example;
    }

    @Override
    public List<String> synonyms() {
        return synonyms;
    }

    @JsonCreator
    public static CompetitorPriceField from(String key) {
        return Optional.ofNullable(key)
                .map(k -> BY_KEY.get(k.strip()))
                .or(() -> Optional.ofNullable(key).map(k -> byLowerKey(k.strip())))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown competitor_prices field '" + key + "'. Expected one of " + BY_KEY.keySet()));
    }

    private static CompetitorPriceField byLowerKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return Stream.of(values())
                .filter(f -> f.key.toLowerCase(Locale.ROOT).equals(lower))
                .findFirst()
                .orElse(null);
    }

    public static List<CompetitorPriceField> required(java.util.Collection<? extends ImportFieldSpec> present) {
        return java.util.Arrays.stream(values())
                .filter(CompetitorPriceField::required)
                .filter(field -> !present.contains(field))
                .toList();
    }

    public static String normalise(String header) {
        return ImportFieldSpec.normalise(header);
    }
}
