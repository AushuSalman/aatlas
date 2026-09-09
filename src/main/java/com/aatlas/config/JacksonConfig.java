package com.aatlas.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * JSON, shaped to match what the prototype's view models already look like.
 *
 * <p>camelCase, because {@code src/lib/platform/api.ts} returns camelCase today and the
 * point of the migration is that the frontend keeps its view models. ISO-8601 strings for
 * instants, never epoch numbers. Money is serialised as a JSON number from
 * {@link java.math.BigDecimal} without scientific notation, so {@code 1234.5600} does not
 * reach a screen as {@code 1.2345E+3}.
 *
 * <p>Unknown properties are rejected on the way in. A client sending {@code quanity} should
 * get a 400, not a silently ignored field and a quote for zero units.
 */
@Configuration
public class JacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer aatlasJacksonCustomizer() {
        return builder -> builder
                .modules(new JavaTimeModule())
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .featuresToEnable(
                        SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
    }
}
