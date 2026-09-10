package com.aatlas.ingest.internal;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Locale;

/** Sync state of a source. Stored lowercase; see {@code data_sources_status_ck}. */
enum DataSourceStatus {

    /** Recorded but no connector has run. ERP and warehouse sources start here. */
    PENDING,
    /** Usable. The sample dataset is connected the moment it is created. */
    CONNECTED,
    SYNCING,
    ERROR;

    @JsonValue
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Converter(autoApply = false)
    static class JpaConverter implements AttributeConverter<DataSourceStatus, String> {

        @Override
        public String convertToDatabaseColumn(DataSourceStatus status) {
            return status == null ? null : status.wireValue();
        }

        @Override
        public DataSourceStatus convertToEntityAttribute(String value) {
            return value == null ? null : DataSourceStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        }
    }
}
