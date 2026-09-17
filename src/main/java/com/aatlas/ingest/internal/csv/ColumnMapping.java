package com.aatlas.ingest.internal.csv;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which column in the file holds which field, for one import kind.
 *
 * <p>Zero-based indexes, because that is what the browser sends and storing anything else
 * would mean converting at the boundary and getting it wrong once.
 */
public record ColumnMapping(ImportKind kind, Map<ImportFieldSpec, Integer> columns) {

    /**
     * Header tokens too generic to identify a column on their own. {@code "Unit Cost (USD)"}
     * contains {@code unit} and {@code cost}; only the second may claim it, and only because
     * {@code unitcost} is five characters and specific.
     */
    static final Set<String> GENERIC = Set.of(
            "units", "price", "cost", "date", "unit", "list", "name", "type", "line", "code", "number", "total",
            "amount", "value", "product", "item", "supplier", "vendor", "branch", "store", "location", "region",
            "country", "currency", "description");

    /** A contained synonym shorter than this is a coincidence, not a match. */
    private static final int MIN_CONTAINS_LENGTH = 5;

    public ColumnMapping {
        kind = kind == null ? ImportKind.SALES : kind;
        columns = columns == null ? Map.of() : Map.copyOf(columns);
    }

    public static ColumnMapping empty(ImportKind kind) {
        return new ColumnMapping(kind, Map.of());
    }

    public Optional<Integer> columnOf(ImportFieldSpec field) {
        return Optional.ofNullable(columns.get(field));
    }

    public boolean has(ImportFieldSpec field) {
        return columns.containsKey(field);
    }

    /** Required fields with no column assigned. Non-empty blocks the import. */
    public List<ImportFieldSpec> missingRequired() {
        return kind.fields().stream()
                .filter(ImportFieldSpec::required)
                .filter(field -> !columns.containsKey(field))
                .toList();
    }

    /** Sales-history detection; see {@link #detect(ImportKind, List)}. */
    public static ColumnMapping detect(List<String> headers) {
        return detect(ImportKind.SALES, headers);
    }

    /**
     * Matches each field of {@code kind} to a column by header name, in two passes.
     *
     * <p><b>Pass 1</b>: for each field in declaration order, claim the first untaken column
     * whose normalised header is exactly one of the field's synonyms. Template headers are
     * exact synonyms, so a template maps entirely in this pass and an exact {@code "quantity"}
     * column is never lost to a fuzzier candidate.
     *
     * <p><b>Pass 2</b>: for each field still unmapped, claim the first untaken column whose
     * header <i>contains</i> a synonym of at least {@value #MIN_CONTAINS_LENGTH} characters
     * that is not in {@link #GENERIC}. {@code "ship_qty"} still finds {@code qty} through
     * {@code shipqty}; {@code "Unit Cost (USD)"} goes to unit cost and never to UOM.
     *
     * <p>A column is claimed at most once. Anything unmatched is left for the user to choose:
     * guessing wrong is worse than not guessing, because a misread price column produces a
     * plausible number that is wrong.
     */
    public static ColumnMapping detect(ImportKind kind, List<String> headers) {
        List<String> normalised = headers.stream().map(ImportFieldSpec::normalise).toList();
        Map<ImportFieldSpec, Integer> mapping = new LinkedHashMap<>();
        Set<Integer> taken = new HashSet<>();

        for (ImportFieldSpec field : kind.fields()) {
            int index = indexOfExact(normalised, taken, field);
            if (index != -1) {
                mapping.put(field, index);
                taken.add(index);
            }
        }
        for (ImportFieldSpec field : kind.fields()) {
            if (mapping.containsKey(field)) {
                continue;
            }
            int index = indexOfContains(normalised, taken, field);
            if (index != -1) {
                mapping.put(field, index);
                taken.add(index);
            }
        }
        return new ColumnMapping(kind, mapping);
    }

    private static int indexOfExact(List<String> normalised, Set<Integer> taken, ImportFieldSpec field) {
        for (int i = 0; i < normalised.size(); i++) {
            if (!taken.contains(i) && field.synonyms().contains(normalised.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfContains(List<String> normalised, Set<Integer> taken, ImportFieldSpec field) {
        for (int i = 0; i < normalised.size(); i++) {
            if (taken.contains(i)) {
                continue;
            }
            String header = normalised.get(i);
            boolean hit = field.synonyms().stream()
                    .anyMatch(synonym -> synonym.length() >= MIN_CONTAINS_LENGTH
                            && !GENERIC.contains(synonym)
                            && header.contains(synonym));
            if (hit) {
                return i;
            }
        }
        return -1;
    }
}
