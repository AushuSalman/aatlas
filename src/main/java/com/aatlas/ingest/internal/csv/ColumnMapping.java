package com.aatlas.ingest.internal.csv;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which column in the file holds which field.
 *
 * <p>Zero-based indexes, because that is what the browser sends and storing anything else
 * would mean converting at the boundary and getting it wrong once.
 */
public record ColumnMapping(Map<ImportField, Integer> columns) {

    public ColumnMapping {
        columns = columns == null ? Map.of() : Map.copyOf(columns);
    }

    public static ColumnMapping empty() {
        return new ColumnMapping(Map.of());
    }

    public Optional<Integer> columnOf(ImportField field) {
        return Optional.ofNullable(columns.get(field));
    }

    public boolean has(ImportField field) {
        return columns.containsKey(field);
    }

    /** Required fields with no column assigned. Non-empty blocks the import. */
    public List<ImportField> missingRequired() {
        return ImportField.required(columns.keySet());
    }

    /**
     * Matches each field to a column by header name.
     *
     * <p>Exact synonym match first across all fields, then a contains match, so
     * {@code "ship_qty"} still finds {@code qty} while an exact {@code "quantity"} column is
     * never lost to a fuzzier candidate. A column is claimed at most once, and fields are
     * considered in declaration order, which puts the required ones first.
     *
     * <p>The contains pass ignores synonyms of three characters or fewer: {@code "cust"}
     * inside {@code "customer_adjustment"} is a coincidence, not a match.
     *
     * <p>Anything unmatched is left for the user to choose. Guessing wrong is worse than
     * not guessing - a misread price column produces a plausible number that is wrong.
     */
    public static ColumnMapping detect(List<String> headers) {
        List<String> normalised = headers.stream().map(ImportField::normalise).toList();
        Map<ImportField, Integer> mapping = new EnumMap<>(ImportField.class);
        Set<Integer> taken = new HashSet<>();

        for (ImportField field : ImportField.values()) {
            int index = indexOfExact(normalised, taken, field);
            if (index == -1) {
                index = indexOfContains(normalised, taken, field);
            }
            if (index != -1) {
                mapping.put(field, index);
                taken.add(index);
            }
        }
        return new ColumnMapping(mapping);
    }

    private static int indexOfExact(List<String> normalised, Set<Integer> taken, ImportField field) {
        for (int i = 0; i < normalised.size(); i++) {
            if (!taken.contains(i) && field.synonyms().contains(normalised.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfContains(List<String> normalised, Set<Integer> taken, ImportField field) {
        for (int i = 0; i < normalised.size(); i++) {
            if (taken.contains(i)) {
                continue;
            }
            String header = normalised.get(i);
            boolean hit = field.synonyms().stream()
                    .anyMatch(synonym -> synonym.length() > 3 && header.contains(synonym));
            if (hit) {
                return i;
            }
        }
        return -1;
    }
}
