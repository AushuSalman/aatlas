package com.aatlas.identity.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * The connection made during onboarding, as the session shows it. The frontend's
 * {@code DataSource} type field for field.
 *
 * @param kind {@code csv}, {@code erp}, {@code warehouse} or {@code sample}
 * @param label shown in the UI, e.g. "Epicor Prophet 21" or "History import"
 * @param detail one line, e.g. "Nightly at 02:00" or "24 months - 3,481 rows"
 */
@Schema(name = "DataSource")
record DataSourceView(String kind, String label, String detail, Instant connectedAt) {
}
