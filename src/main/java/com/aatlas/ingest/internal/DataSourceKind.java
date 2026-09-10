package com.aatlas.ingest.internal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where a tenant's history comes from. Wire values match the frontend's
 * {@code DataSource.kind} union and the {@code data_sources_kind_ck} constraint.
 */
enum DataSourceKind {

    CSV("csv"),
    ERP("erp"),
    WAREHOUSE("warehouse"),
    SAMPLE("sample");

    private static final Map<String, DataSourceKind> BY_WIRE_VALUE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(DataSourceKind::wireValue, Function.identity()));

    private final String wireValue;

    DataSourceKind(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static DataSourceKind from(String value) {
        DataSourceKind kind = value == null ? null : BY_WIRE_VALUE.get(value.strip().toLowerCase(Locale.ROOT));
        if (kind == null) {
            throw new IllegalArgumentException(
                    "Unknown data source kind '" + value + "'. Expected one of " + BY_WIRE_VALUE.keySet());
        }
        return kind;
    }

    @Converter(autoApply = false)
    static class JpaConverter implements AttributeConverter<DataSourceKind, String> {

        @Override
        public String convertToDatabaseColumn(DataSourceKind kind) {
            return kind == null ? null : kind.wireValue();
        }

        @Override
        public DataSourceKind convertToEntityAttribute(String value) {
            return value == null ? null : from(value);
        }
    }
}
