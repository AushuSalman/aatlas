package com.aatlas.common.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The seeded generator must reproduce every row the TypeScript produced. */
class SeededTest {

    private static JsonNode golden() throws Exception {
        try (InputStream in = SeededTest.class.getResourceAsStream("/golden/rand.json")) {
            assertThat(in).as("golden/rand.json on the test classpath").isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void matchesEveryGoldenRow() throws Exception {
        int checked = 0;
        for (JsonNode row : golden()) {
            String fn = row.get("fn").asText();
            JsonNode in = row.get("input");
            JsonNode out = row.get("output");
            switch (fn) {
                case "hashString" -> {
                    assertThat(Seeded.hashString(in.get("s").asText())).as(row.toString()).isEqualTo(out.asLong());
                    checked++;
                }
                case "rand", "seeded" -> {
                    assertThat(Seeded.rand(in.get("key").asText(), in.path("salt").asText("")))
                            .as(row.toString())
                            .isCloseTo(out.asDouble(), within(1e-12));
                    checked++;
                }
                case "randRange", "seededRange" -> {
                    assertThat(Seeded.randRange(in.get("key").asText(), in.get("salt").asText(), in.get("min").asDouble(), in.get("max").asDouble()))
                            .as(row.toString())
                            .isCloseTo(out.asDouble(), within(1e-9));
                    checked++;
                }
                case "randInt" -> {
                    assertThat(Seeded.randInt(in.get("key").asText(), in.get("salt").asText(), in.get("min").asInt(), in.get("max").asInt()))
                            .as(row.toString())
                            .isEqualTo(out.asInt());
                    checked++;
                }
                case "pick" -> {
                    List<JsonNode> list = new ArrayList<>();
                    in.get("list").forEach(list::add);
                    assertThat(Seeded.pick(in.get("key").asText(), in.get("salt").asText(), list)).as(row.toString()).isEqualTo(out);
                    checked++;
                }
                default -> {
                    /* constants and helpers pinned elsewhere */
                }
            }
        }
        assertThat(checked).isGreaterThan(900);
    }

    @Test
    void absoluteValueOfMinInt() {
        // Math.abs(Integer.MIN_VALUE) is still negative in Java; the port must not fall into that.
        assertThat(Seeded.hashString("")).isPositive();
    }
}
