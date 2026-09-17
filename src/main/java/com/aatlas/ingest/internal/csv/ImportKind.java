package com.aatlas.ingest.internal.csv;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The four files a tenant can load. Decides the field set, the validator and the loader.
 *
 * <p>Wire keys match {@code import_batches_kind_ck}; {@link #sampleFile()} is the classpath
 * copy of the Hardin sample the sample loader and the download endpoint both read.
 */
public enum ImportKind {

    SALES("sales", "Sales history", "samples/sales-history.csv"),
    PURCHASES("purchases", "Purchase history", "samples/purchase-history.csv"),
    PRODUCTS("products", "Product master & prices", "samples/products.csv"),
    COMPETITOR_PRICES("competitor_prices", "Competitor prices", "samples/competitor-prices.csv");

    private static final Map<String, ImportKind> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(ImportKind::key, Function.identity()));

    private final String key;
    private final String label;
    private final String sampleFile;

    ImportKind(String key, String label, String sampleFile) {
        this.key = key;
        this.label = label;
        this.sampleFile = sampleFile;
    }

    @JsonValue
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String sampleFile() {
        return sampleFile;
    }

    /** The fields of this kind, in detection order. */
    public List<ImportFieldSpec> fields() {
        return switch (this) {
            case SALES -> List.of(ImportField.values());
            case PURCHASES -> List.of(PurchaseField.values());
            case PRODUCTS -> List.of(ProductField.values());
            case COMPETITOR_PRICES -> List.of(CompetitorPriceField.values());
        };
    }

    /** A field of this kind by wire key. */
    public ImportFieldSpec field(String fieldKey) {
        return switch (this) {
            case SALES -> ImportField.from(fieldKey);
            case PURCHASES -> PurchaseField.from(fieldKey);
            case PRODUCTS -> ProductField.from(fieldKey);
            case COMPETITOR_PRICES -> CompetitorPriceField.from(fieldKey);
        };
    }

    public List<ImportFieldSpec> requiredFields() {
        return fields().stream().filter(ImportFieldSpec::required).toList();
    }

    @JsonCreator
    public static ImportKind from(String key) {
        ImportKind kind = key == null ? null : BY_KEY.get(key.strip().toLowerCase(Locale.ROOT));
        if (kind == null) {
            throw new IllegalArgumentException(
                    "Unknown import kind '" + key + "'. Expected one of " + BY_KEY.keySet());
        }
        return kind;
    }
}
