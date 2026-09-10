package com.aatlas.decisions.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The one fixture {@code getHistory}'s port needs: an item number's human name. Ports
 * {@code intel/catalog.ts}'s {@code metaFor(itemNumber).shortName}, read directly from
 * {@code seed/products.json}'s {@code shortName} column (already the same value
 * {@code PRODUCT_META} carries - see {@code API-BRIEF.md}'s seed file notes) rather than
 * duplicating {@code analytics}' fuller fixture reader for one field.
 */
final class ProductNames {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Row(String itemNumber, String description, String shortName) {
    }

    private static final Map<String, Row> BY_ITEM = load();

    private ProductNames() {
    }

    private static Map<String, Row> load() {
        try {
            Resource resource = new PathMatchingResourcePatternResolver().getResource("classpath:seed/products.json");
            try (InputStream in = resource.getInputStream()) {
                List<Row> rows = new ObjectMapper().readValue(in, new TypeReference<List<Row>>() {
                });
                return rows.stream().collect(Collectors.toMap(Row::itemNumber, r -> r));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read seed/products.json", e);
        }
    }

    /** The short human name for an item, or the item number / description itself when unknown. */
    static String shortName(String itemNumber, String fallbackDescription) {
        Row row = BY_ITEM.get(itemNumber);
        if (row != null && row.shortName() != null) {
            return row.shortName();
        }
        return fallbackDescription != null ? fallbackDescription : itemNumber;
    }
}
