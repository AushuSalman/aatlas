package com.aatlas.suppliers.internal.csv;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which column in a supplier export holds which field. Zero-based, as the browser sends it.
 */
public record SupplierColumnMapping(Map<SupplierField, Integer> columns) {

    public SupplierColumnMapping {
        columns = columns == null ? Map.of() : Map.copyOf(columns);
    }

    public static SupplierColumnMapping empty() {
        return new SupplierColumnMapping(Map.of());
    }

    Optional<Integer> columnOf(SupplierField field) {
        return Optional.ofNullable(columns.get(field));
    }

    boolean has(SupplierField field) {
        return columns.containsKey(field);
    }

    /** Required fields with no column assigned. Non-empty blocks the import. */
    public List<SupplierField> missingRequired() {
        return SupplierField.requiredButAbsent(columns.keySet());
    }

    /**
     * Matches each field to a column by header name.
     *
     * <p>Exact synonym first, then a contains match, so {@code vendor_name} finds
     * {@code name} without an exact {@code name} column ever being lost to a fuzzier one.
     * Synonyms of three characters or fewer are excluded from the contains pass, because
     * {@code tel} inside {@code hotel_group} is a coincidence rather than a match.
     */
    public static SupplierColumnMapping detect(List<String> headers) {
        List<String> normalised = headers.stream().map(SupplierField::normalise).toList();
        Map<SupplierField, Integer> mapping = new EnumMap<>(SupplierField.class);
        Set<Integer> taken = new HashSet<>();

        for (SupplierField field : SupplierField.values()) {
            int index = exactMatch(normalised, taken, field);
            if (index == -1) {
                index = containsMatch(normalised, taken, field);
            }
            if (index != -1) {
                mapping.put(field, index);
                taken.add(index);
            }
        }
        return new SupplierColumnMapping(mapping);
    }

    private static int exactMatch(List<String> headers, Set<Integer> taken, SupplierField field) {
        for (int i = 0; i < headers.size(); i++) {
            if (!taken.contains(i) && field.synonyms().contains(headers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static int containsMatch(List<String> headers, Set<Integer> taken, SupplierField field) {
        for (int i = 0; i < headers.size(); i++) {
            if (taken.contains(i)) {
                continue;
            }
            String header = headers.get(i);
            if (field.synonyms().stream().anyMatch(s -> s.length() > 3 && header.contains(s))) {
                return i;
            }
        }
        return -1;
    }
}
