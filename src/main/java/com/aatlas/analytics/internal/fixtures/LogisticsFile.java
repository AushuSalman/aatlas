package com.aatlas.analytics.internal.fixtures;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/** The whole of {@code seed/logistics.json}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LogisticsFile(List<RegionFixture> regions, Map<String, OriginFixture> origins) {
}
