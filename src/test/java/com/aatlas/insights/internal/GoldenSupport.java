package com.aatlas.insights.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Shared plumbing for this module's golden-file tests: load a fixture, compare structurally
 * with numbers treated as equal regardless of {@code IntNode} vs {@code DoubleNode} (a Java
 * {@code double} field always serialises with a decimal point; {@code JSON.stringify} does
 * not for a whole number) - the same tolerant comparison
 * {@code suppliers.SupplierEngineGoldenTest} uses.
 */
final class GoldenSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    // Java records serialise a null field explicitly (`"tone":null`); JSON.stringify on the
    // TypeScript side omits an absent optional field entirely. Same field, same meaning, two
    // different encodings that have nothing to do with the engine being ported - excluded here
    // so the structural comparison below is not the thing that fails.
    private static final ObjectMapper TREE = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private GoldenSupport() {
    }

    static JsonNode load(String resource) throws Exception {
        try (InputStream in = GoldenSupport.class.getResourceAsStream("/golden/" + resource)) {
            assertThat(in).as("golden/%s on the test classpath", resource).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    static JsonNode tree(Object value) {
        return TREE.valueToTree(value);
    }

    static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.isNumber() && b.isNumber()) {
            return a.asDouble() == b.asDouble();
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<String> names = a.fieldNames();
            while (names.hasNext()) {
                String f = names.next();
                if (!b.has(f) || !jsonEquals(a.get(f), b.get(f))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquals(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    static void assertJsonEquals(JsonNode expected, JsonNode actual, String label) {
        assertThat(jsonEquals(actual, expected))
                .as("%s%nexpected=%s%nactual=%s", label, expected, actual)
                .isTrue();
    }
}
