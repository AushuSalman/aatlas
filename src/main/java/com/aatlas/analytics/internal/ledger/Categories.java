package com.aatlas.analytics.internal.ledger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buying is organised by category, not by SKU. Ported verbatim from
 * {@code platform/procurement.ts}'s {@code CATEGORIES}/{@code CATEGORY_OF} - a hand-mapped
 * scheme distinct from the catalogue's own marketing category (Plumbing/HVAC/...).
 */
public final class Categories {

    public record Category(String key, String label) {
    }

    public static final List<Category> ALL = List.of(
            new Category("pipe", "Pipe & fittings"),
            new Category("tube", "Copper tube"),
            new Category("valves", "Valves & controls"),
            new Category("equipment", "Heating & cooling"),
            new Category("fixtures", "Fixtures"));

    private static final Map<String, String> CATEGORY_OF = new LinkedHashMap<>();

    static {
        CATEGORY_OF.put("HRD304148", "pipe");
        CATEGORY_OF.put("HRD290145", "pipe");
        CATEGORY_OF.put("HRD661204", "pipe");
        CATEGORY_OF.put("HRD512066", "pipe");
        CATEGORY_OF.put("HRD874019", "pipe");
        CATEGORY_OF.put("HRD248813", "pipe");
        CATEGORY_OF.put("HRD118902", "tube");
        CATEGORY_OF.put("HRD107744", "tube");
        CATEGORY_OF.put("HRD772310", "valves");
        CATEGORY_OF.put("HRD450871", "equipment");
        CATEGORY_OF.put("HRD983377", "equipment");
        CATEGORY_OF.put("HRD335590", "fixtures");
    }

    private Categories() {
    }

    public static String categoryOf(String itemNumber) {
        return CATEGORY_OF.getOrDefault(itemNumber, "pipe");
    }

    public static String labelOf(String key) {
        return ALL.stream().filter(c -> c.key().equals(key)).map(Category::label).findFirst().orElse(key);
    }
}
